"""
Current-trip candidate-context adjustment — pure decision logic
===============================================================
The V3 model estimates a fair one-night price from a hotel's own attributes.
It cannot see that THIS trip's dates, neighbourhood and budget tier happen to
be pricing softer or harder than the model's overall expectation. The other
hotels the Agent surfaced for the same trip do carry that information, so the
factor here is the median of

    live comparable one-night price / raw V3 P50

measured across those OTHER candidates, and never across the hotel being
judged.

WHAT THIS IS NOT
----------------
This is NOT a market price, a global market price, or a real market price.
The candidates are a handful of hotels filtered by one traveller's budget and
location, so the factor describes THIS trip's candidate set and nothing wider.
Every name here says CURRENT_TRIP_CANDIDATE_CONTEXT for that reason.

WHY THE MEDIAN
--------------
Measured live across 14 Mumbai hotels, the per-hotel ratio ranged 0.53 to 2.63
(median 0.95, IQR 0.80-1.08); the 2.63 was a single landmark property. With
only a few context hotels a mean would follow that outlier, while across all
1001 four-hotel subsets of that sample the MEDIAN stayed within 0.66-1.13.

WHY THE CLAMP IS 0.70-1.30
--------------------------
From the same simulation, the median of four sat inside 0.77-1.07 for 90% of
subsets and never left 0.66-1.13. A 0.70-1.30 clamp therefore does not bind in
normal operation - it only catches pathological input (nearly every candidate
sold out, a currency mix-up, a stale profile). Tighter bounds would clip real
signal; wider ones would let one bad probe through.

That evidence is one city on one check-in date, so these bounds are a
deliberately conservative starting point, not a validated constant.
"""
from __future__ import annotations

import math
import statistics
from typing import Iterable, Optional

CONTEXT_BASIS = "CURRENT_TRIP_CANDIDATE_CONTEXT"

# Two is the smallest set that has a middle; one hotel is an anecdote, not a
# distribution, and would hand the whole factor to a single probe.
MIN_CONTEXT_HOTELS = 2
CLAMP_LOW = 0.70
CLAMP_HIGH = 1.30

# Reasons an adjustment did not happen. Reported so a caller can always tell
# "no adjustment" apart from "adjustment computed to 1.0".
REASON_APPLIED = "APPLIED"
REASON_NO_CANDIDATES = "NO_CANDIDATES"
REASON_NO_CONTEXT_HOTELS = "NO_CONTEXT_HOTELS"
REASON_INSUFFICIENT_CONTEXT = "INSUFFICIENT_CONTEXT"
REASON_HISTORICAL_EXCLUDED = "HISTORICAL_PATH_EXCLUDED"
REASON_PROBE_FAILED = "CONTEXT_PROBE_FAILED"


def no_adjustment(reason: str, valid_count: int = 0,
                  context_hotels: Optional[list] = None) -> dict:
    """The identity adjustment. Everything downstream degrades to this."""
    return {
        "applied": False,
        "factor": 1.0,
        "rawFactor": None,
        "clamped": False,
        "validContextHotelCount": valid_count,
        "reason": reason,
        "basis": CONTEXT_BASIS,
        "clampRange": [CLAMP_LOW, CLAMP_HIGH],
        "contextHotels": context_hotels or [],
    }


def context_ratio(live_price, raw_p50) -> Optional[float]:
    """live / raw_p50, or None when either side is unusable.

    Rejects NaN and infinities explicitly: they would propagate silently
    through a median and produce a nonsense factor.
    """
    try:
        live, p50 = float(live_price), float(raw_p50)
    except (TypeError, ValueError):
        return None
    if not (math.isfinite(live) and math.isfinite(p50)):
        return None
    if live <= 0 or p50 <= 0:
        return None
    return live / p50


def compute_context_factor(ratios: Iterable[float],
                           context_hotels: Optional[list] = None) -> dict:
    """Median of the context ratios, clamped. Never sees the target hotel."""
    usable = [r for r in ratios
              if r is not None and math.isfinite(r) and r > 0]

    if len(usable) < MIN_CONTEXT_HOTELS:
        return no_adjustment(REASON_INSUFFICIENT_CONTEXT, len(usable), context_hotels)

    raw = statistics.median(usable)
    factor = min(max(raw, CLAMP_LOW), CLAMP_HIGH)
    return {
        "applied": True,
        "factor": round(factor, 6),
        "rawFactor": round(raw, 6),
        "clamped": factor != raw,
        "validContextHotelCount": len(usable),
        "reason": REASON_APPLIED,
        "basis": CONTEXT_BASIS,
        "clampRange": [CLAMP_LOW, CLAMP_HIGH],
        "contextHotels": context_hotels or [],
    }
