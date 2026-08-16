"""
India hotel fair-price predictor (hybrid V2.1) — production inference
=====================================================================
Answers exactly one question:

    "For the user's FIRST night, at 1 room / 2 adults / 1 night, is this
     hotel's current INR quote inside the historical fair-price range?"

It does NOT judge a multi-night booking total, and it does NOT forecast
future prices. `currentPrice` is never a model feature - it is compared to
the prediction afterwards.

Routing (unchanged from V1):
    unambiguous normalized hotel name AND >= 5 training offers -> HISTORICAL (B2)
    otherwise                                                  -> ML (M2D)

Post-processing differs by path, deliberately:

    HISTORICAL   sort -> x1.3707 -> band
    ML (V3 M2)   sort -> CQR widen -> x1.0 -> band

TWO THINGS THAT MUST NOT BE CONFLATED:

1. Conformal calibration belongs to the ML model only. The HISTORICAL
   quantiles are a hotel's own empirical history and were never part of the
   conformal procedure, so widening them with the ML qhat would be unjustified.

2. The temporal factor belongs to the HISTORICAL path only. B2 is built from
   March 2020 PromptCloud prices and must be rebased. The V3 ML model was
   trained on 2026 LiteAPI-native rates and is ALREADY on the current price
   level - multiplying it by the CPI factor would double-count inflation.
"""
from __future__ import annotations

import math
from pathlib import Path
from typing import Optional

import joblib
import numpy as np
import pandas as pd
from catboost import CatBoostRegressor

from ml.india_context_adjustment import (
    REASON_HISTORICAL_EXCLUDED, REASON_NO_CANDIDATES, no_adjustment,
)
from ml.india_serving_features import (
    V3_CATEGORICAL, V3_FEATURES, calendar_features, normalize_name,
    room_category, serving_city, v3_features,
)

MODELS = Path(__file__).resolve().parent.parent / "models"
CBM_PATH = MODELS / "hotel_price_india_v3_m2.cbm"
META_PATH = MODELS / "hotel_price_india_v3_m2.joblib"

SUPPORTED_MARKET = "IN"
# Training lead-time support is strictly 0..15 days (crawl 2020-03-01..03-24 vs
# check-in 2020-03-01..04-07). Outside it the model would extrapolate, so we
# decline instead of clamping or inventing a fallback.
MIN_LEAD_DAYS = 0
MAX_LEAD_DAYS = 15
SUPPORTED_CURRENCY = "INR"
COMPARISON_BASIS = "PER_NIGHT_1ROOM_2ADULTS"
PRICE_BASIS_HISTORICAL = "TEMPORALLY_ADJUSTED_2026_07_REFERENCE"
PRICE_BASIS_ML = "LITEAPI_NATIVE_2026_SNAPSHOT"
_INDIA_ALIASES = {"IN", "IND", "INDIA"}


def is_india(country) -> bool:
    """LiteAPI country values vary (ISO-2, ISO-3, full name); accept all three."""
    return normalize_name(country).upper().replace(" ", "") in _INDIA_ALIASES


class IndiaHotelPricePredictor:
    """Loads the frozen V2.1 artifact once and serves fair-price verdicts."""

    def __init__(self) -> None:
        self._model: Optional[CatBoostRegressor] = None
        self._art: Optional[dict] = None

    # ---- lazy load so import never fails when the artifact is absent ----
    def _ensure(self) -> bool:
        if self._art is not None:
            return True
        if not (CBM_PATH.exists() and META_PATH.exists()):
            return False
        self._art = joblib.load(META_PATH)
        self._model = CatBoostRegressor()
        self._model.load_model(str(CBM_PATH))
        self._hotel_room = self._art["b2_hotel_room"].set_index(["hotel_key", "room_category"])
        self._hotel = self._art["b2_hotel"].set_index("hotel_key")
        self._known = set(self._art["known_hotel_keys"])
        self._ambiguous = set(self._art["ambiguous_hotel_keys"])
        self._city_aliases = self._art["city_aliases"]
        m = self._art["metadata"]
        self._qhat = m["calibration"]["qhat"]
        self._factor_historical = m["temporalAdjustment"]["historicalPathFactor"]
        self._factor_ml = m["temporalAdjustment"]["mlPathFactor"]
        self._tolerance = m["businessTolerance"]
        return True

    @property
    def metadata(self) -> dict:
        return self._art["metadata"] if self._ensure() else {}

    @property
    def model_version(self) -> str:
        return self.metadata.get("modelVersion", "unavailable")

    # ------------------------------------------------------------------ core
    def predict(self, *, hotel_name: str, current_price: float, currency: str,
                market: str, booking_date, check_in_date,
                room_name: str = None, board_type: str = None,
                board_name: str = None, refundable_tag: str = None,
                city: str = None, rating: float = None, review_count: int = None,
                stars: float = None, chain: str = None, hotel_type_id: int = None,
                facility_ids: list = None,
                comparison_offer_selection: str = "CHEAPEST_COMPARABLE_ONE_NIGHT",
                context: dict = None) -> dict:
        if not self._ensure():
            return _unavailable("MODEL_ERROR")
        if not is_india(market):
            return _unavailable("UNSUPPORTED_MARKET")
        if (currency or "").strip().upper() != SUPPORTED_CURRENCY:
            return _unavailable("UNSUPPORTED_MARKET")
        try:
            if current_price is None:
                return _unavailable("INVALID_INPUT")
            price = float(current_price)
            # reject NaN / +-Infinity / zero / negative
            if not math.isfinite(price) or price <= 0:
                return _unavailable("INVALID_INPUT")
            cal = calendar_features(check_in_date, booking_date)
        except Exception:
            return _unavailable("INVALID_INPUT")
        # booking after check-in is malformed input, not an unsupported horizon
        if cal["lead_time_days"] < MIN_LEAD_DAYS:
            return _unavailable("INVALID_INPUT")
        if cal["lead_time_days"] > MAX_LEAD_DAYS:
            return _unavailable("UNSUPPORTED_LEAD_TIME")

        hotel_key = normalize_name(hotel_name)
        if not hotel_key:
            return _unavailable("INVALID_INPUT")

        room_cat = room_category(room_name)
        matched = hotel_key in self._known and hotel_key not in self._ambiguous
        q = self._lookup_b2(hotel_key, room_cat) if matched else None

        if q is not None:
            source = "HISTORICAL"
            price_basis = PRICE_BASIS_HISTORICAL
            p25, p50, p75 = sorted(float(x) for x in q)
            # NO conformal adjustment: these are the hotel's own empirical
            # quantiles and were not part of the ML calibration procedure.
            # 2020-basis quantiles DO need the CPI rebasing.
            f = self._factor_historical
        else:
            source, matched = "ML", False
            price_basis = PRICE_BASIS_ML
            row = v3_features(
                market=serving_city(city, self._city_aliases),
                stars=stars, rating=rating, review_count=review_count,
                chain=chain, hotel_type_id=hotel_type_id, facility_ids=facility_ids,
                room_name=room_name, board_type=board_type, board_name=board_name,
                refundable_tag=refundable_tag,
                lead_time_days=cal["lead_time_days"],
            )
            p25, p50, p75 = sorted(float(x) for x in self._predict_ml(row))
            p25, p75 = self._conformalize(p25, p50, p75)      # ML PATH ONLY
            # V3 was trained on 2026 LiteAPI-native rates - already current.
            f = self._factor_ml

        p25, p50, p75 = p25 * f, p50 * f, p75 * f

        # Round the band BEFORE deciding, so the verdict is always consistent
        # with the numbers we publish - a caller echoing back decisionLow must
        # get FAIR, not CHEAP off a hidden extra decimal.
        raw_p25, raw_p50, raw_p75 = round(p25, 2), round(p50, 2), round(p75, 2)
        raw_low, raw_high = self._band(raw_p25, raw_p50, raw_p75)

        # The current-trip candidate context belongs to the ML path only. The
        # HISTORICAL quantiles are the hotel's OWN price history, already
        # rebased by the CPI factor above; scaling them again by a factor
        # derived from other hotels would stack a second, differently-sourced
        # correction onto a correction that already exists.
        ctx = dict(context) if context else no_adjustment(REASON_NO_CANDIDATES)
        if source != "ML":
            ctx = no_adjustment(REASON_HISTORICAL_EXCLUDED)

        if ctx["applied"]:
            cf = float(ctx["factor"])
            p25, p50, p75 = (round(raw_p25 * cf, 2), round(raw_p50 * cf, 2),
                             round(raw_p75 * cf, 2))
            low, high = self._band(p25, p50, p75)
        else:
            p25, p50, p75, low, high = raw_p25, raw_p50, raw_p75, raw_low, raw_high

        level = "CHEAP" if price < low else ("EXPENSIVE" if price > high else "FAIR")

        return {
            "predictionAvailable": True,
            "predictionSource": source,
            "modelVersion": self.model_version,
            # Final, context-adjusted result. These keep the original names so
            # existing consumers need no change.
            "fairPriceP25": p25,
            "fairPriceP50": p50,
            "fairPriceP75": p75,
            "decisionLow": low,
            "decisionHigh": high,
            # The model's own output, always published so the adjustment can be
            # audited and undone. Identical to the fields above when no
            # adjustment applied.
            "rawFairPriceP25": raw_p25,
            "rawFairPriceP50": raw_p50,
            "rawFairPriceP75": raw_p75,
            "rawDecisionLow": raw_low,
            "rawDecisionHigh": raw_high,
            "contextAdjustmentApplied": ctx["applied"],
            "contextAdjustmentFactor": ctx["factor"],
            "contextAdjustmentRawFactor": ctx["rawFactor"],
            "contextAdjustmentClamped": ctx["clamped"],
            "contextAdjustmentReason": ctx["reason"],
            "contextAdjustmentBasis": ctx["basis"],
            "validContextHotelCount": ctx["validContextHotelCount"],
            "currentComparablePrice": round(price, 2),
            "priceLevel": level,
            "currency": SUPPORTED_CURRENCY,
            "market": SUPPORTED_MARKET,
            "comparisonBasis": COMPARISON_BASIS,
            "comparisonOfferSelection": comparison_offer_selection,
            "hotelMatchedHistorically": matched,
            "priceBasis": price_basis,
            "temporalAdjustmentFactor": f,
        }

    def _band(self, p25: float, p50: float, p75: float):
        """Business decision band around the quantiles."""
        tol = self._tolerance
        return (round(min(p25, (1 - tol) * p50), 2),
                round(max(p75, (1 + tol) * p50), 2))

    # -------------------------------------------------------------- routing
    def _lookup_b2(self, hotel_key: str, room_cat: str):
        """hotel x room_category, then hotel-level. None -> caller falls back to ML."""
        try:
            r = self._hotel_room.loc[(hotel_key, room_cat)]
            return (r.p25, r.p50, r.p75)
        except KeyError:
            pass
        try:
            r = self._hotel.loc[hotel_key]
            return (r.p25, r.p50, r.p75)
        except KeyError:
            return None

    def _predict_ml(self, row: dict):
        df = pd.DataFrame([row])[V3_FEATURES]          # frozen order is contractual
        for c in V3_CATEGORICAL:
            df[c] = df[c].astype(str)
        return tuple(self._model.predict(df)[0])

    def _conformalize(self, p25: float, p50: float, p75: float):
        """Frozen normalized CQR (qhat from the CAL hotels): widen the
        endpoints only. P50 is untouched by construction."""
        width = p75 - p25
        return p25 - self._qhat * width, p75 + self._qhat * width


def _unavailable(reason: str) -> dict:
    return {"predictionAvailable": False, "reason": reason}
