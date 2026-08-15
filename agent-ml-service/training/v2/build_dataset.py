"""
V2 unified dataset build — 8 cities -> one USD-normalized training table
=======================================================================
Implements the "Option B" decision from the V2 city-validation report:
merge the 8 product-aligned Inside Airbnb cities into a single table, filter
to the hotel-comparable short-stay segment, and normalize every price to USD
at fixed June-2026 ECB reference rates (see fx_rates.py).

Inputs : training/data/v2/{city}_listings.csv.gz  (gitignored, from validate_cities.py)
Outputs: training/data/v2/hotel_price_v2_dataset.parquet  (gitignored)
         training/v2/reference/build_report.json          (committed, auditable)

Run: .venv/bin/python training/v2/build_dataset.py
"""
from __future__ import annotations

import json
import re
from pathlib import Path

import pandas as pd

from fx_rates import CITY_CURRENCY, USD_RATES, verify_rates

DATA_DIR = Path(__file__).resolve().parent.parent / "data" / "v2"
REPORT_PATH = Path(__file__).resolve().parent / "reference" / "build_report.json"
OUT_PATH = DATA_DIR / "hotel_price_v2_dataset.parquet"

# The hotel-comparable market segment: listings bookable for a normal trip.
# Applied uniformly to every city (not only the min-stay-regulated ones) so
# the filter is a single documented rule rather than per-city tuning.
MAX_MINIMUM_NIGHTS = 7

# Sanity bounds on the USD target. Chosen to drop data-entry noise and
# ultra-luxury rows that no city-level model can serve, NOT to make metrics
# look good; the count dropped at each bound is reported.
MIN_PRICE_USD = 10.0
MAX_PRICE_USD = 2000.0

HOTEL_RE = re.compile(r"hotel", re.IGNORECASE)
CURRENCY_RE = re.compile(r'"currency":\s*"([A-Z]{3})"')
# Bangkok's quote JSON has currency:null; its line items still carry the baht
# symbol, which is what actually pins the currency there.
SYMBOL_HINTS = {"฿": "THB", "¥": "JPY", "€": "EUR", "£": "GBP"}

# Columns read from the 90-column source file. Everything price-derived other
# than the target is deliberately absent: `price`, `price_quote_total_price`
# and `estimated_revenue_l365d` are all functions of the target (leakage).
SOURCE_COLUMNS = [
    "id", "last_scraped", "latitude", "longitude", "neighbourhood_cleansed",
    "property_type", "room_type", "accommodates", "bedrooms", "beds",
    "bathrooms", "bathrooms_text", "minimum_nights", "maximum_nights",
    "review_scores_rating", "number_of_reviews",
    "price_quote_price_per_night", "price_quote_checkin_date",
    "price_quote_checkout_date", "price_quote_raw",
]

BATHROOM_RE = re.compile(r"([\d.]+)")


def parse_bathrooms(row_bathrooms: pd.Series, row_text: pd.Series) -> pd.Series:
    """`bathrooms` is sparsely populated; recover it from `bathrooms_text`.

    "1.5 shared baths" -> 1.5, "Half-bath" -> 0.5, "Shared half-bath" -> 0.5.
    """
    text = row_text.astype(str)
    parsed = pd.to_numeric(text.str.extract(BATHROOM_RE, expand=False), errors="coerce")
    parsed = parsed.mask(text.str.contains("half", case=False, na=False) & parsed.isna(), 0.5)
    return row_bathrooms.fillna(parsed)


def resolve_currency(city: str, raw: pd.Series) -> tuple[pd.Series, dict]:
    """Per-row currency from the quote JSON, with symbol/city fallback.

    Returns the resolved series plus the evidence trail for the build report,
    because "which currency is this column in" is the exact failure that
    corrupted V1's target.
    """
    text = raw.fillna("")
    iso = text.str.extract(CURRENCY_RE, expand=False)

    symbol = pd.Series(pd.NA, index=raw.index, dtype="object")
    for glyph, code in SYMBOL_HINTS.items():
        symbol = symbol.mask(symbol.isna() & text.str.contains(glyph, regex=False), code)

    expected = CITY_CURRENCY[city]
    resolved = iso.fillna(symbol).fillna(expected)

    # Contradiction between explicit ISO code and the city assumption is a
    # hard failure, not something to silently coerce.
    conflicts = int((iso.notna() & (iso != expected)).sum())
    if conflicts:
        raise ValueError(f"{city}: {conflicts} rows carry a currency other than {expected}")

    return resolved, {
        "expected": expected,
        "from_iso_code": int(iso.notna().sum()),
        "from_symbol_fallback": int((iso.isna() & symbol.notna()).sum()),
        "from_city_assumption": int((iso.isna() & symbol.isna()).sum()),
    }


def build_city(city: str, path: Path) -> tuple[pd.DataFrame, dict]:
    df = pd.read_csv(path, low_memory=False, usecols=SOURCE_COLUMNS)
    stats: dict = {"raw_rows": len(df)}

    quote = pd.to_numeric(df["price_quote_price_per_night"], errors="coerce")
    df = df[quote.notna() & (quote > 0)].copy()
    stats["usable_rows"] = len(df)

    df = df[pd.to_numeric(df["minimum_nights"], errors="coerce") <= MAX_MINIMUM_NIGHTS].copy()
    stats["short_stay_rows"] = len(df)

    currency, evidence = resolve_currency(city, df["price_quote_raw"])
    stats["currency_evidence"] = evidence

    price_local = pd.to_numeric(df["price_quote_price_per_night"], errors="coerce")
    rates = currency.map(USD_RATES)
    out = pd.DataFrame({
        "listing_id": df["id"].astype("int64"),
        "city": city,
        "source_currency": currency.values,
        "price_local": price_local.values,
        "price_usd": (price_local / rates).values,
    })

    checkin = pd.to_datetime(df["price_quote_checkin_date"], errors="coerce")
    checkout = pd.to_datetime(df["price_quote_checkout_date"], errors="coerce")
    scraped = pd.to_datetime(df["last_scraped"], errors="coerce")

    out["checkin_date"] = checkin.values
    out["checkout_date"] = checkout.values
    out["scraped_date"] = scraped.values
    out["nights"] = (checkout - checkin).dt.days.values
    # Booking lead time: the quote was requested around the scrape date. A city
    # is scraped over several days, so `last_scraped` can land a day or two
    # after the quoted check-in; clamp those at 0 rather than emit negatives.
    out["lead_time_days"] = (checkin - scraped).dt.days.clip(lower=0).values
    out["arrival_month"] = checkin.dt.month.values
    out["arrival_dow"] = checkin.dt.dayofweek.values
    out["is_weekend_checkin"] = (checkin.dt.dayofweek >= 4).values

    out["latitude"] = df["latitude"].values
    out["longitude"] = df["longitude"].values
    out["neighbourhood"] = df["neighbourhood_cleansed"].values
    out["property_type"] = df["property_type"].values
    out["room_type"] = df["room_type"].values
    out["is_hotel_like"] = df["property_type"].astype(str).str.contains(HOTEL_RE).values
    out["accommodates"] = pd.to_numeric(df["accommodates"], errors="coerce").values
    out["bedrooms"] = pd.to_numeric(df["bedrooms"], errors="coerce").values
    out["beds"] = pd.to_numeric(df["beds"], errors="coerce").values
    out["bathrooms"] = parse_bathrooms(
        pd.to_numeric(df["bathrooms"], errors="coerce"), df["bathrooms_text"]
    ).values
    out["minimum_nights"] = pd.to_numeric(df["minimum_nights"], errors="coerce").values
    out["review_scores_rating"] = pd.to_numeric(df["review_scores_rating"], errors="coerce").values
    out["number_of_reviews"] = pd.to_numeric(df["number_of_reviews"], errors="coerce").values
    # `instant_bookable` is NOT carried: verified 100% null in all 8 snapshots.

    before = len(out)
    out = out[out["checkin_date"].notna() & out["nights"].between(1, 30)]
    stats["dropped_bad_dates"] = before - len(out)

    before = len(out)
    out = out[out["price_usd"] >= MIN_PRICE_USD]
    stats["dropped_below_min_price"] = before - len(out)

    before = len(out)
    out = out[out["price_usd"] <= MAX_PRICE_USD]
    stats["dropped_above_max_price"] = before - len(out)

    stats["final_rows"] = len(out)
    stats["hotel_like_rows"] = int(out["is_hotel_like"].sum())
    stats["price_usd_median"] = round(float(out["price_usd"].median()), 2)
    stats["price_usd_p95"] = round(float(out["price_usd"].quantile(0.95)), 2)
    return out, stats


def main() -> None:
    verify_rates()
    print("FX rates verified against the committed ECB series\n")

    frames, report = [], {}
    for city in CITY_CURRENCY:
        path = DATA_DIR / f"{city}_listings.csv.gz"
        if not path.exists():
            raise FileNotFoundError(f"{path} missing - run training/v2/validate_cities.py first")
        frame, stats = build_city(city, path)
        frames.append(frame)
        report[city] = stats
        print(
            f"{city:16s} raw={stats['raw_rows']:6d} usable={stats['usable_rows']:6d} "
            f"short={stats['short_stay_rows']:6d} final={stats['final_rows']:6d} "
            f"median=${stats['price_usd_median']:.0f}"
        )

    data = pd.concat(frames, ignore_index=True)
    data.to_parquet(OUT_PATH, index=False)

    totals = {
        "rows": len(data),
        "cities": int(data["city"].nunique()),
        "listings": int(data["listing_id"].nunique()),
        "hotel_like_rows": int(data["is_hotel_like"].sum()),
        "price_usd_median": round(float(data["price_usd"].median()), 2),
        "checkin_min": str(data["checkin_date"].min().date()),
        "checkin_max": str(data["checkin_date"].max().date()),
        "null_rate": {
            c: round(float(data[c].isna().mean()), 4)
            for c in ("bedrooms", "beds", "bathrooms", "review_scores_rating")
        },
    }
    REPORT_PATH.write_text(json.dumps(
        {"per_city": report, "totals": totals, "fx_rates_usd": USD_RATES,
         "filters": {"max_minimum_nights": MAX_MINIMUM_NIGHTS,
                     "min_price_usd": MIN_PRICE_USD, "max_price_usd": MAX_PRICE_USD}},
        indent=2,
    ))

    print(f"\nTOTAL {totals['rows']:,} rows / {totals['cities']} cities / "
          f"{totals['listings']:,} listings, hotel-like {totals['hotel_like_rows']:,}")
    print(f"checkin {totals['checkin_min']} .. {totals['checkin_max']}, "
          f"median ${totals['price_usd_median']}")
    print(f"null rates: {totals['null_rate']}")
    print(f"\nwrote {OUT_PATH}\nwrote {REPORT_PATH}")


if __name__ == "__main__":
    main()
