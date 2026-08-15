"""
Price Advisor — model-driven price range + best-buy timing advice
=================================================================
Serves the P25/P50/P75 quantile model trained by
training/train_price_advisor.py. Answers three questions for a planned stay:

  1. Expected per-night price RANGE (P25..P75)
  2. WHEN to book (lead-time scan -> cheapest lead days)
  3. Which MONTH is cheapest (arrival-month scan)

All advice comes from scanning the fitted quantile models — no external
price scraping. Limitations are stated honestly in the response message:
the training dataset has no city/star-rating features, so the advice is
city-agnostic (driven by lead time, month, stay length, guests).
"""
from __future__ import annotations

import math
from datetime import date
from pathlib import Path
from typing import Optional

import joblib
import pandas as pd

MODELS = Path(__file__).resolve().parent.parent / "models"
ARTIFACT_PATH = MODELS / "price_advisor_v1.joblib"

# Lead-time grid the scan runs over (days before check-in). Chosen to cover
# the dataset's realistic booking window (0..~240 days) with meaningful steps.
LEAD_TIME_GRID = [0, 3, 7, 14, 21, 30, 45, 60, 90, 120, 150, 180, 210, 240]

# Fixed context rows: the dataset has no city/star features, so we hold
# these at their most common values and say so in the response message.
DEFAULT_CONTEXT = {
    "hotel": "City Hotel",
    "reserved_room_type": "A",       # most common room code (double room)
    "market_segment": "Direct",
    "deposit_type": "No Deposit",
    "customer_type": "Transient",
}


class PriceAdvisor:
    """Lazily loads the quantile artifact and answers price-advice queries."""

    def __init__(self) -> None:
        self._art: Optional[dict] = None
        self._models: Optional[dict] = None

    def _ensure(self) -> bool:
        if self._art is not None:
            return True
        if not ARTIFACT_PATH.exists():
            return False
        self._art = joblib.load(ARTIFACT_PATH)
        self._models = self._art["models"]
        self._cols = self._art["feature_columns"]
        self._cat = self._art["categorical_features"]
        return True

    @property
    def metadata(self) -> dict:
        return self._art["metadata"] if self._ensure() else {}

    # ------------------------------------------------------------- core
    def advise(self, *, check_in_date, check_out_date, number_of_guests: int,
               room_type: str = "double") -> dict:
        if not self._ensure():
            return {"predictionAvailable": False, "reason": "MODEL_ERROR"}

        try:
            check_in = date.fromisoformat(str(check_in_date))
            check_out = date.fromisoformat(str(check_out_date))
            nights = (check_out - check_in).days
            guests = int(number_of_guests)
            if nights <= 0 or guests <= 0:
                return {"predictionAvailable": False, "reason": "INVALID_INPUT"}
        except (ValueError, TypeError):
            return {"predictionAvailable": False, "reason": "INVALID_INPUT"}

        room_code = room_type.lower() if room_type else "double"
        # Dataset room codes are single letters; double is by far the most
        # common, so map everything else onto the dataset's own codes where
        # a sensible match exists, else keep the default.
        code_map = {"single": "B", "double": "A", "twin": "D", "suite": "G"}
        context = dict(DEFAULT_CONTEXT)
        context["reserved_room_type"] = code_map.get(room_code, "A")

        base = {
            "nights": nights,
            "number_of_guests": guests,
            "arrival_month_num": check_in.month,
            **context,
        }

        # 1) Lead-time scan: cheapest point on the model's pricing curve.
        lead_rows = pd.DataFrame([
            {**base, "lead_time": lt} for lt in LEAD_TIME_GRID
        ])
        lead_curve = self._predict_quantiles(lead_rows)  # list of (p25,p50,p75)
        best_idx = min(range(len(LEAD_TIME_GRID)),
                       key=lambda i: lead_curve[i][1])
        best_lead = LEAD_TIME_GRID[best_idx]
        p25, p50, p75 = lead_curve[best_idx]
        last_minute_p50 = lead_curve[0][1]
        saving_pct = 0.0
        if last_minute_p50 > 0:
            saving_pct = round((last_minute_p50 - p50) / last_minute_p50 * 100, 1)
        # The curve still descends at the far end of the grid -> the honest
        # advice is "the earlier the better", not a fake precise day count.
        at_grid_edge = best_idx in (0, len(LEAD_TIME_GRID) - 1)
        if at_grid_edge and best_idx == len(LEAD_TIME_GRID) - 1:
            best_lead = None  # signal "as early as possible"

        # 2) Month scan: cheapest arrival month, holding the best lead time
        #    (when the curve never bottoms out, use the grid's far end - the
        #    month comparison is about seasonality, not exact lead days).
        month_lead = best_lead if best_lead is not None else LEAD_TIME_GRID[-1]
        month_rows = pd.DataFrame([
            {**base, "lead_time": month_lead, "arrival_month_num": m}
            for m in range(1, 13)
        ])
        month_curve = self._predict_quantiles(month_rows)
        best_month_idx = min(range(12), key=lambda i: month_curve[i][1])
        cheapest_month = best_month_idx + 1

        return {
            "predictionAvailable": True,
            "currency": "USD",
            "modelStatus": self.metadata.get("modelStatus", "trained"),
            "modelVersion": self.metadata.get("modelVersion", "unavailable"),
            "priceRangePerNight": {
                "p25": round(p25, 2),
                "p50": round(p50, 2),
                "p75": round(p75, 2),
            },
            "totalPriceRange": {
                "p25": round(p25 * nights, 2),
                "p50": round(p50 * nights, 2),
                "p75": round(p75 * nights, 2),
            },
            "buyTiming": {
                "recommendedLeadDays": best_lead,
                "cheapestPricePerNight": round(p50, 2),
                "savingVsLastMinutePercent": saving_pct,
                "message": (
                    f"Booking as early as possible is the cheapest strategy "
                    f"(the model's lead-time curve keeps falling past {LEAD_TIME_GRID[-1]} days; "
                    f"est. {round(p50, 2)} USD/night, {saving_pct}% below a last-minute booking)."
                    if best_lead is None else
                    f"Booking {best_lead} days ahead is the cheapest point on "
                    f"the model's lead-time curve (est. {round(p50, 2)} USD/night, "
                    f"{'saving' if saving_pct > 0 else 'about'} "
                    f"{saving_pct}% vs a last-minute booking)."
                ),
            },
            "monthlyCurve": [
                {
                    "month": m,
                    "p50PerNight": round(month_curve[m - 1][1], 2),
                }
                for m in range(1, 13)
            ],
            "cheapestMonth": {
                "month": cheapest_month,
                "p50PerNight": round(month_curve[best_month_idx][1], 2),
            },
            "message": (
                "Model-driven advice from a quantile model trained on 72k real "
                "hotel bookings (Hotel Booking Demand). The dataset has no city "
                "or star-rating fields, so advice is city-agnostic - driven by "
                "lead time, arrival month, stay length and guest count. Price "
                "ranges are per night, USD, before taxes."
            ),
        }

    # ------------------------------------------------------------- internals
    def _predict_quantiles(self, rows: pd.DataFrame) -> list[tuple[float, float, float]]:
        """Predict (p25, p50, p75) for each row, sorted ascending."""
        for c in self._cat:
            rows[c] = rows[c].astype(str)
        out = []
        for _, row in rows.iterrows():
            frame = pd.DataFrame([row])[self._cols]
            q = [float(self._models[q].predict(frame)[0]) for q in (0.25, 0.5, 0.75)]
            out.append(tuple(sorted(q)))
        return out


def _unavailable(reason: str) -> dict:
    return {"predictionAvailable": False, "reason": reason}
