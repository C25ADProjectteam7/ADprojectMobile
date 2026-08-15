"""
India hotel fair-price predictor (hybrid) — production inference
================================================================
Answers exactly one question:

    "For the user's FIRST night, at 1 room / 2 adults / 1 night, is this
     hotel's current INR quote inside the historical fair-price range?"

It does NOT judge a multi-night booking total, and it does NOT forecast
future prices. `currentPrice` is never a model feature - it is compared to
the prediction afterwards.

Routing (frozen):
    unambiguous normalized hotel name AND >= 5 training offers -> HISTORICAL (B2)
    otherwise                                                  -> ML (M1 CatBoost)

Both paths return P25/P50/P75, which are then sorted (CatBoost quantile
regression crossed on ~0.023% of validation rows) and widened into the
business decision band before the verdict.
"""
from __future__ import annotations

from pathlib import Path
from typing import Optional

import math

import joblib
import pandas as pd
from catboost import CatBoostRegressor

from ml.india_serving_features import (
    CATEGORICAL, FEATURES, breakfast_category_from_liteapi,
    calendar_features, cancellation_category_from_liteapi, normalize_name,
    room_category,
)

MODELS = Path(__file__).resolve().parent.parent / "models"
CBM_PATH = MODELS / "hotel_price_india_hybrid_v1.cbm"
META_PATH = MODELS / "hotel_price_india_hybrid_v1.joblib"

SUPPORTED_MARKET = "IN"
# Training lead-time support is strictly 0..15 days (crawl 2020-03-01..03-24 vs
# check-in 2020-03-01..04-07). Outside it the model would extrapolate, so we
# decline instead of clamping or inventing a fallback.
MIN_LEAD_DAYS = 0
MAX_LEAD_DAYS = 15
SUPPORTED_CURRENCY = "INR"
COMPARISON_BASIS = "PER_NIGHT_1ROOM_2ADULTS"
_INDIA_ALIASES = {"IN", "IND", "INDIA"}


def is_india(country) -> bool:
    """LiteAPI country values vary (ISO-2, ISO-3, full name); accept all three."""
    return normalize_name(country).upper().replace(" ", "") in _INDIA_ALIASES


class IndiaHotelPricePredictor:
    """Loads the frozen artifact once and serves fair-price verdicts."""

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
                comparison_offer_selection: str = "CHEAPEST_COMPARABLE_ONE_NIGHT") -> dict:
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

        row = {
            "hotel_key": hotel_key,
            "room_category": room_category(room_name),
            "breakfast_category": breakfast_category_from_liteapi(board_type, board_name),
            "cancellation_category": cancellation_category_from_liteapi(refundable_tag),
            **cal,
        }

        matched = hotel_key in self._known and hotel_key not in self._ambiguous
        if matched:
            q = self._lookup_b2(hotel_key, row["room_category"])
            source = "HISTORICAL"
        else:
            q = None
        if q is None:
            q = self._predict_ml(row)
            source = "ML"
            matched = False

        p25, p50, p75 = sorted(float(x) for x in q)      # deterministic sort
        tol = self.metadata.get("businessTolerance", 0.15)
        low = min(p25, (1 - tol) * p50)
        high = max(p75, (1 + tol) * p50)

        level = "CHEAP" if price < low else ("EXPENSIVE" if price > high else "FAIR")

        return {
            "predictionAvailable": True,
            "predictionSource": source,
            "modelVersion": self.model_version,
            "fairPriceP25": round(p25, 2),
            "fairPriceP50": round(p50, 2),
            "fairPriceP75": round(p75, 2),
            "decisionLow": round(low, 2),
            "decisionHigh": round(high, 2),
            "currentComparablePrice": round(price, 2),
            "priceLevel": level,
            "currency": SUPPORTED_CURRENCY,
            "market": SUPPORTED_MARKET,
            "comparisonBasis": COMPARISON_BASIS,
            "comparisonOfferSelection": comparison_offer_selection,
            "hotelMatchedHistorically": matched,
        }

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
        df = pd.DataFrame([row])[FEATURES]
        for c in CATEGORICAL:
            df[c] = df[c].astype(str)
        return tuple(self._model.predict(df)[0])


def _unavailable(reason: str) -> dict:
    return {"predictionAvailable": False, "reason": reason}
