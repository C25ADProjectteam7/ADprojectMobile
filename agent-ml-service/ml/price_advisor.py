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
               room_type: str = "double", booking_date=None, current_price=None) -> dict:
        if not self._ensure():
            return {"prediction_available": False, "reason": "MODEL_ERROR"}

        try:
            check_in = date.fromisoformat(str(check_in_date))
            check_out = date.fromisoformat(str(check_out_date))
            nights = (check_out - check_in).days
            guests = int(number_of_guests)
            if nights <= 0 or guests <= 0:
                return {"prediction_available": False, "reason": "INVALID_INPUT"}
        except (ValueError, TypeError):
            return {"prediction_available": False, "reason": "INVALID_INPUT"}

        # The hotel's CURRENT rate selects the price band (economy/mid/
        # premium) so the market range compared against is the band's own -
        # a $360 Manhattan hotel must not be judged against an $88 average.
        try:
            current = float(current_price) if current_price is not None else None
        except (ValueError, TypeError):
            current = None
        tier = self._tier_of(current)

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
            "adr_tier": tier,
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

        # 3) "Is NOW a good time to book?" - compare the price at the
        #    CURRENT lead time (today -> check-in) against the curve's best
        #    point. Booking after the recommended window is "too late";
        #    booking within it is a good time.
        current_timing = self._current_timing(
            base, check_in, booking_date, lead_curve, p50, saving_pct,
        )

        # 4) Band-relative market range: when the current rate sits far above
        #    the band's median (beyond what the dataset contains), scale the
        #    band range around the current rate so the comparison is with
        #    "hotels at THIS price level", not the band average.
        scale = None
        if current is not None and current > 0 and p50 > 0:
            ratio = current / p50
            if ratio >= 1.5:
                scale = ratio
        if scale:
            p25, p50, p75 = p25 * scale, p50 * scale, p75 * scale
            scale_applied = True
        else:
            scale_applied = False

        return {
            "prediction_available": True,
            "currency": "USD",
            "model_status": self.metadata.get("modelStatus", "trained"),
            "model_version": self.metadata.get("modelVersion", "unavailable"),
            "price_range_per_night": {
                "p25": round(p25, 2),
                "p50": round(p50, 2),
                "p75": round(p75, 2),
            },
            "total_price_range": {
                "p25": round(p25 * nights, 2),
                "p50": round(p50 * nights, 2),
                "p75": round(p75 * nights, 2),
            },
            "buy_timing": {
                "recommended_lead_days": best_lead,
                "cheapest_price_per_night": round(p50, 2),
                "saving_vs_last_minute_percent": saving_pct,
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
            "monthly_curve": [
                {
                    "month": m,
                    "p50_per_night": round(month_curve[m - 1][1], 2),
                }
                for m in range(1, 13)
            ],
            "cheapest_month": {
                "month": cheapest_month,
                "p50_per_night": round(month_curve[best_month_idx][1], 2),
            },
            "current_timing": current_timing,
            "price_tier": tier,
            "tier_range_note": (
                None if current is None else self._tier_note(current, tier, p25, p75)
            ),
            "message": (
                "Model-driven advice from a quantile model trained on 72k real "
                "hotel bookings (Hotel Booking Demand). The dataset has no city "
                "or star-rating fields, so advice is city-agnostic - driven by "
                "lead time, arrival month, stay length and guest count. Price "
                "ranges are per night, USD, before taxes."
            ),
        }

    # ------------------------------------------------------------- internals
    def _tier_of(self, price: float | None) -> str:
        """Price band for a nightly rate, matching the training feature."""
        meta = self.metadata
        labels = meta.get("tierLabels", ["economy", "mid", "premium"])
        edges = meta.get("tierEdges", [0.0, 100.0, 200.0, float("inf")])
        if price is None:
            return "mid"
        for i, edge in enumerate(edges[:-1]):
            if price < edges[i + 1]:
                return labels[i]
        return labels[-1]

    def _tier_note(self, current: float, tier: str, p25: float, p75: float) -> str:
        """Human note explaining which band the comparison used."""
        return (
            f"Compared against the {tier} band market range "
            f"({round(p25, 2)}-{round(p75, 2)} USD/night), selected from the "
            f"hotel's current rate."
        )

    def _current_timing(self, base: dict, check_in, booking_date,
                        lead_curve: list, best_p50: float, saving_pct: float) -> dict:
        """Verdict on whether booking RIGHT NOW is a good time.

        current lead days = check_in - booking_date (clamped to the grid).
        The price at that lead time is compared to the curve's best point:
          - within 5%  of the best  -> GOOD_TIME  ("now is a good time")
          - within 15% above best   -> OK         ("acceptable, earlier is better")
          - more than 15% above     -> TOO_LATE   ("you're late - prices have climbed")
        When booking_date is missing, there is nothing to judge - the field is
        simply absent and the caller falls back to the buyTiming advice."""
        if booking_date is None:
            return None
        try:
            book = date.fromisoformat(str(booking_date))
        except (ValueError, TypeError):
            return None
        current_lead = (check_in - book).days
        if current_lead < 0:
            return {
                "current_lead_days": None,
                "verdict": "INVALID_INPUT",
                "message": "Booking date is after check-in - please correct the dates.",
            }

        # Price at the current lead time: interpolate on the scanned curve.
        if current_lead <= LEAD_TIME_GRID[0]:
            cur_p50 = lead_curve[0][1]
        elif current_lead >= LEAD_TIME_GRID[-1]:
            cur_p50 = lead_curve[-1][1]
        else:
            # linear interpolation between the two bracketing grid points
            for i in range(len(LEAD_TIME_GRID) - 1):
                lo, hi = LEAD_TIME_GRID[i], LEAD_TIME_GRID[i + 1]
                if lo <= current_lead <= hi:
                    frac = (current_lead - lo) / (hi - lo)
                    cur_p50 = lead_curve[i][1] * (1 - frac) + lead_curve[i + 1][1] * frac
                    break
            else:
                cur_p50 = lead_curve[-1][1]

        premium = 0.0
        if best_p50 > 0:
            premium = (cur_p50 - best_p50) / best_p50 * 100  # % above the best point
        if premium <= 5.0:
            verdict = "GOOD_TIME"
            message = (
                f"Now is a good time to book ({current_lead} days ahead) - the "
                f"price is essentially at the curve's best point."
            )
        elif premium <= 15.0:
            verdict = "OK"
            message = (
                f"Booking now ({current_lead} days ahead) is {premium:.1f}% above "
                f"the curve's best price - acceptable, but booking earlier would "
                f"save more."
            )
        else:
            verdict = "TOO_LATE"
            message = (
                f"You're {current_lead} days out and prices have climbed "
                f"{premium:.1f}% above the curve's best point - book now or accept "
                f"the higher price."
            )

        return {
            "current_lead_days": current_lead,
            "current_price_per_night": round(cur_p50, 2),
            "best_price_per_night": round(best_p50, 2),
            "premium_vs_best_percent": round(premium, 1),
            "verdict": verdict,
            "message": message,
        }

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
    return {"prediction_available": False, "reason": reason}
