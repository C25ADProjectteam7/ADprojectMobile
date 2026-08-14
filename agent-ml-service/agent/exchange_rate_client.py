"""
Exchange rate client - USD to SGD conversion for itinerary cost display.
Uses Frankfurter (https://frankfurter.dev), a free, no-key-required API
backed by European Central Bank reference rates, updated once per business
day. Suitable for display/estimate purposes - NOT for real-time trading or
final payment amounts. All bookings are still transacted in USD (Duffel/
LiteAPI); this conversion is informational only.

Failure handling: a live rate fetch is retried automatically (via
retry_on_timeout) since this is a read-only, side-effect-free call. If it
still fails, falls back to the last successfully fetched rate cached in
memory for this process. If neither is available (e.g. first call of the
process fails), falls back to a rough hardcoded reference rate. Callers
should check the returned "source" field to decide how confidently to
present the figure to the user.
"""
import httpx
from agent.http_utils import retry_on_timeout

FRANKFURTER_BASE_URL = "https://api.frankfurter.dev/v1"

# Rough historical USD->SGD reference, used only if both a live fetch and
# the in-memory cache are unavailable. Should be rare - update periodically
# if actual rates drift significantly from this value over time.
_FALLBACK_RATE = 1.28

# In-memory cache of the last successfully fetched rate, so a transient
# Frankfurter outage doesn't immediately fall back to the rough static rate.
_cached_rate = {"value": None}


@retry_on_timeout(max_attempts=3)
async def _fetch_usd_to_sgd_rate() -> float:
    """Fetches the live USD->SGD rate from Frankfurter. Raises on failure -
    callers should use get_usd_to_sgd_rate() instead, which handles the
    fallback chain."""
    async with httpx.AsyncClient(timeout=10.0) as client:
        response = await client.get(
            f"{FRANKFURTER_BASE_URL}/latest",
            params={"base": "USD", "symbols": "SGD"},
        )
        response.raise_for_status()
        return response.json()["rates"]["SGD"]


async def get_usd_to_sgd_rate() -> dict:
    """Returns {"rate": float, "source": str}, where source is one of:
    - "live": freshly fetched from Frankfurter just now
    - "cached": a live fetch failed, used the last known-good rate from
      earlier in this process's lifetime instead
    - "fallback": no live fetch has ever succeeded in this process, used a
      rough hardcoded reference rate

    This function never raises - callers can always rely on getting a usable
    rate back, and should use "source" to decide what confidence to convey
    to the end user.
    """
    try:
        rate = await _fetch_usd_to_sgd_rate()
        _cached_rate["value"] = rate
        return {"rate": rate, "source": "live"}
    except (httpx.TimeoutException, httpx.HTTPStatusError, KeyError):
        if _cached_rate["value"] is not None:
            return {"rate": _cached_rate["value"], "source": "cached"}
        return {"rate": _FALLBACK_RATE, "source": "fallback"}