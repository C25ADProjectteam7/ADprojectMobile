"""
V2 FX reference rates — fixed, auditable, pinned to the snapshot month
=====================================================================
The Inside Airbnb snapshots used by V2 were scraped 2026-06-14 .. 2026-06-30,
so prices are converted to USD at the **June 2026 ECB monthly average**, not
at live FX. Fixed rates keep training reproducible: re-running the pipeline
next month must not silently move the target.

Source: European Central Bank daily reference rates, retrieved via the
Frankfurter API (https://api.frankfurter.dev/v1/2026-06-01..2026-06-30
?base=USD&symbols=JPY,THB,EUR,GBP,SGD,AUD) on 2026-08-11. The full daily
series is committed at `training/v2/reference/ecb_usd_rates_2026-06.json`
so every number here can be re-derived; `verify_rates()` does exactly that.

Rates are expressed as UNITS OF LOCAL CURRENCY PER 1 USD.
    price_usd = price_local / RATE[currency]
"""
from __future__ import annotations

import json
import statistics
from pathlib import Path

RATES_JSON = Path(__file__).resolve().parent / "reference" / "ecb_usd_rates_2026-06.json"

# June 2026 ECB monthly averages (22 business days). Intra-month spread was
# 1.6%-4.3%, so the monthly mean is a fair representative rate rather than
# an arbitrary single day.
USD_RATES: dict[str, float] = {
    "USD": 1.0,
    "JPY": 160.72500,
    "THB": 32.88486,
    "EUR": 0.86828,
    "GBP": 0.74994,
    "SGD": 1.28790,
    "AUD": 1.42320,
}

# Fallback only. 7 of 8 cities carry an explicit ISO currency code inside
# listings.csv's `price_quote_raw` JSON; Bangkok's is null there, but its
# line-item price strings are prefixed with a literal "B" baht symbol, so THB
# is confirmed by symbol evidence rather than assumed from magnitude.
CITY_CURRENCY: dict[str, str] = {
    "tokyo": "JPY",
    "bangkok": "THB",
    "paris": "EUR",
    "barcelona": "EUR",
    "london": "GBP",
    "new-york-city": "USD",
    "singapore": "SGD",
    "sydney": "AUD",
}


def to_usd(amount: float, currency: str) -> float:
    """Convert a local-currency amount to USD at the pinned reference rate."""
    return amount / USD_RATES[currency]


def verify_rates(tolerance: float = 5e-4) -> dict[str, float]:
    """Recompute the monthly averages from the committed ECB series.

    Guards against the hardcoded table drifting away from its stated source.
    Raises ValueError if any rate disagrees beyond `tolerance` (relative).
    """
    series = json.loads(RATES_JSON.read_text())["rates"]
    recomputed: dict[str, float] = {}
    for currency, pinned in USD_RATES.items():
        if currency == "USD":
            recomputed[currency] = 1.0
            continue
        daily = [day[currency] for day in series.values() if currency in day]
        if not daily:
            raise ValueError(f"{currency} missing from {RATES_JSON}")
        actual = statistics.mean(daily)
        recomputed[currency] = actual
        if abs(actual - pinned) / pinned > tolerance:
            raise ValueError(
                f"{currency}: pinned {pinned} != recomputed {actual:.5f} "
                f"from {len(daily)} ECB business days"
            )
    return recomputed


if __name__ == "__main__":
    for currency, rate in verify_rates().items():
        print(f"{currency}: {rate:.5f} per USD  (pinned {USD_RATES[currency]})")
    print("OK - pinned rates match the committed ECB series")
