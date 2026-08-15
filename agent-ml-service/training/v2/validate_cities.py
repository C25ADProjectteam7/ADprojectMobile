"""
V2 multi-city download + validation — Inside Airbnb listings data
==================================================================
Downloads (if missing) the latest-snapshot listings.csv.gz for the 8
product-aligned cities, validates each against the V2 requirements
(docs/ml/hotel-price-v2-dataset-requirements.md), and prints a comparable
per-city summary.

Raw files land in training/data/v2/ (gitignored). Reviews/calendar are NOT
downloaded — calendar no longer carries prices (verified 2026-08-11) and
reviews are irrelevant to price prediction.

Run: .venv/bin/python training/v2/validate_cities.py
"""
from pathlib import Path
import re
import urllib.parse
import urllib.request

import pandas as pd

DATA_DIR = Path(__file__).resolve().parent.parent / "data" / "v2"

# Snapshot URLs taken from the officially published download index
# (insideairbnb.com/get-the-data), latest snapshot per city as of 2026-08-11.
CITIES: dict[str, str] = {
    "tokyo": "https://data.insideairbnb.com/japan/kantō/tokyo/2026-06-30/data/listings.csv.gz",
    "bangkok": "https://data.insideairbnb.com/thailand/central-thailand/bangkok/2026-06-29/data/listings.csv.gz",
    "paris": "https://data.insideairbnb.com/france/ile-de-france/paris/2026-06-16/data/listings.csv.gz",
    "london": "https://data.insideairbnb.com/united-kingdom/england/london/2026-06-19/data/listings.csv.gz",
    "new-york-city": "https://data.insideairbnb.com/united-states/ny/new-york-city/2026-06-14/data/listings.csv.gz",
    "singapore": "https://data.insideairbnb.com/singapore/sg/singapore/2026-06-29/data/listings.csv.gz",
    "sydney": "https://data.insideairbnb.com/australia/nsw/sydney/2026-06-16/data/listings.csv.gz",
    "barcelona": "https://data.insideairbnb.com/spain/catalonia/barcelona/2026-06-24/data/listings.csv.gz",
}

REQUIRED_COLUMNS = [
    "id", "price", "price_quote_price_per_night", "price_quote_checkin_date",
    "price_quote_checkout_date", "accommodates", "room_type", "property_type",
    "minimum_nights", "latitude", "longitude",
]

HOTEL_PATTERN = re.compile(r"hotel", re.IGNORECASE)
CURRENCY_SYMBOL_RE = re.compile(r"^\s*([^\d\s.,-]+)")


def download_if_missing(city: str, url: str) -> Path:
    dest = DATA_DIR / f"{city}_listings.csv.gz"
    if dest.exists():
        print(f"[dl] {city}: already present ({dest.stat().st_size/1e6:.1f} MB)")
        return dest
    print(f"[dl] {city}: downloading ...")
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    # percent-encode non-ASCII path segments (e.g. Tokyo's "kantō")
    parts = urllib.parse.urlsplit(url)
    safe_url = urllib.parse.urlunsplit(
        (parts.scheme, parts.netloc, urllib.parse.quote(parts.path), parts.query, parts.fragment)
    )
    urllib.request.urlretrieve(safe_url, dest)
    print(f"[dl] {city}: done ({dest.stat().st_size/1e6:.1f} MB)")
    return dest


def snapshot_date(url: str) -> str:
    return re.search(r"/(\d{4}-\d{2}-\d{2})/", url).group(1)


def validate_city(city: str, url: str) -> dict:
    path = download_if_missing(city, url)
    df = pd.read_csv(path, low_memory=False)

    missing_cols = [c for c in REQUIRED_COLUMNS if c not in df.columns]

    raw_rows = len(df)
    quote = pd.to_numeric(df.get("price_quote_price_per_night"), errors="coerce")
    usable = df[quote.notna() & (quote > 0)].copy()
    usable_quote = quote[quote.notna() & (quote > 0)]

    # Empirical currency evidence: leading symbol of the raw price string.
    symbols = (
        df["price"].dropna().astype(str).str.extract(CURRENCY_SYMBOL_RE, expand=False)
        .value_counts().head(3).to_dict()
    )

    nights = (
        pd.to_datetime(usable["price_quote_checkout_date"], errors="coerce")
        - pd.to_datetime(usable["price_quote_checkin_date"], errors="coerce")
    ).dt.days

    hotel_like = usable["property_type"].astype(str).str.contains(HOTEL_PATTERN).sum()

    return {
        "city": city,
        "snapshot": snapshot_date(url),
        "raw_rows": raw_rows,
        "usable_rows": len(usable),
        "parse_rate": f"{len(usable)/raw_rows*100:.0f}%" if raw_rows else "n/a",
        "missing_cols": missing_cols or "none",
        "currency_symbols": symbols,
        "price_median": round(usable_quote.median(), 1) if len(usable) else None,
        "price_p95": round(usable_quote.quantile(0.95), 1) if len(usable) else None,
        "accommodates_nonnull": f"{usable['accommodates'].notna().mean()*100:.0f}%" if len(usable) else "n/a",
        "room_types": usable["room_type"].value_counts().to_dict() if len(usable) else {},
        "hotel_like_rows": int(hotel_like),
        "quote_nights_median": nights.median() if len(usable) else None,
        "quote_nights_p90": nights.quantile(0.90) if len(usable) else None,
        "min_nights_median": usable["minimum_nights"].median() if len(usable) else None,
        "min_nights_over_28_pct": f"{(usable['minimum_nights'] > 28).mean()*100:.0f}%" if len(usable) else "n/a",
        # hotel-comparable short-stay subset: the market segment our product
        # actually asks about (min_nights <= 7 filters out monthly-rental rows)
        "short_stay_rows": int((usable["minimum_nights"] <= 7).sum()) if len(usable) else 0,
        "short_stay_hotel_like": int(
            (usable["minimum_nights"] <= 7)
            [usable["property_type"].astype(str).str.contains(HOTEL_PATTERN)].sum()
        ) if len(usable) else 0,
    }


def main():
    results = []
    for city, url in CITIES.items():
        try:
            results.append(validate_city(city, url))
        except Exception as e:  # keep batch running; report failures honestly
            results.append({"city": city, "error": repr(e)})

    print("\n" + "=" * 100)
    for r in results:
        print()
        for k, v in r.items():
            print(f"  {k:24s} {v}")


if __name__ == "__main__":
    main()
