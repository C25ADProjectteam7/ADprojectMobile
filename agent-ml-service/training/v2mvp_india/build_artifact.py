"""
Build the FINAL production artifact — no retraining decisions, no tuning
=======================================================================
Fits the already-approved hybrid on the FULL cleaned dataset using the frozen
schema, seed and hyperparameters, then writes:

    models/hotel_price_india_hybrid_v1.cbm      CatBoost MultiQuantile (M1)
    models/hotel_price_india_hybrid_v1.joblib   B2 tables + metadata

Never overwrites hotel_price_baseline.joblib or hotel_price_v2_osaka_tiers.joblib.

Run: .venv/bin/python training/v2mvp_india/build_artifact.py
"""
from __future__ import annotations

import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

import catboost
import joblib
import numpy as np
import pandas as pd
from catboost import CatBoostRegressor, Pool

ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(ROOT))
from ml.india_serving_features import (  # noqa: E402
    BREAKFAST_CATEGORIES, CANCELLATION_CATEGORIES, CATEGORICAL, FEATURES,
    ROOM_CATEGORIES, breakfast_category, cancellation_category,
    normalize_name, room_category,
)

CLEAN = ROOT / "training" / "data" / "promptcloud" / "india_offers_clean.parquet"
RAW_ZIP = ROOT / "training" / "data" / "promptcloud" / "price.zip"
CBM = ROOT / "models" / "hotel_price_india_hybrid_v1.cbm"
META = ROOT / "models" / "hotel_price_india_hybrid_v1.joblib"
VALIDATION = Path(__file__).resolve().parent / "reference" / "serving_parity.json"

Q = (0.25, 0.50, 0.75)
CB = dict(loss_function="MultiQuantile:alpha=0.25,0.5,0.75", iterations=500,
          depth=6, learning_rate=0.1, random_seed=42, verbose=0, thread_count=-1)
MIN_HOTEL_OBS = 5
BUSINESS_TOLERANCE = 0.15
MODEL_VERSION = "india-hybrid-v1"


def main() -> None:
    d = pd.read_parquet(CLEAN)
    d["hotel_key"] = d.hotel_name.map(normalize_name)
    d["room_category"] = d.room_norm.map(room_category)
    d["breakfast_category"] = d.bf.map(breakfast_category)
    d["cancellation_category"] = d.canc.map(cancellation_category)
    amb_counts = d.groupby("hotel_key").hotel_id.nunique()
    ambiguous = set(amb_counts[amb_counts > 1].index)
    print(f"[data] {len(d):,} offers | {d.hotel_key.nunique():,} hotel keys "
          f"| {len(ambiguous):,} ambiguous")

    # ---- B2: hotel-own quantiles, unambiguous keys with >= MIN_HOTEL_OBS ----
    elig = d[~d.hotel_key.isin(ambiguous)]
    counts = elig.groupby("hotel_key").size()
    known_keys = set(counts[counts >= MIN_HOTEL_OBS].index)
    b2src = elig[elig.hotel_key.isin(known_keys)]

    hr = b2src.groupby(["hotel_key", "room_category"]).price.quantile(list(Q)).unstack()
    hr.columns = ["p25", "p50", "p75"]
    hr["n"] = b2src.groupby(["hotel_key", "room_category"]).size()
    hr = hr[hr.n >= MIN_HOTEL_OBS]

    h = b2src.groupby("hotel_key").price.quantile(list(Q)).unstack()
    h.columns = ["p25", "p50", "p75"]
    h["n"] = b2src.groupby("hotel_key").size()
    h = h[h.n >= MIN_HOTEL_OBS]

    print(f"[B2] known hotel keys {len(known_keys):,} "
          f"(covering {d.hotel_key.isin(known_keys).mean()*100:.1f}% of offers)")
    print(f"[B2] hotel x room cells {len(hr):,} | hotel-level rows {len(h):,}")

    # ---- M1: CatBoost MultiQuantile on the full dataset ----
    m = CatBoostRegressor(**CB)
    m.fit(Pool(d[FEATURES], d.price, cat_features=CATEGORICAL))
    CBM.parent.mkdir(parents=True, exist_ok=True)
    m.save_model(str(CBM))
    print(f"[M1] saved {CBM.name} ({CBM.stat().st_size/1e6:.2f} MB)")

    sha = hashlib.sha256(RAW_ZIP.read_bytes()).hexdigest() if RAW_ZIP.exists() else None
    val = json.loads(VALIDATION.read_text())["results"] if VALIDATION.exists() else {}
    val_summary = {k: {"HYBRID_pinball": v["metrics"]["HYBRID"]["pinball"],
                       "B2_pinball": v["metrics"]["B2 hotel-own"]["pinball"],
                       "M1_pinball": v["metrics"]["M1 hotel-aware"]["pinball"],
                       "known_pct": v["known_pct"]} for k, v in val.items()}

    meta = {
        "modelVersion": MODEL_VERSION,
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "architecture": {
            "known": "B2 hotel-own historical quantiles",
            "unknown": "M1 CatBoost MultiQuantile",
            "routing": "unambiguous normalized hotel name AND >= 5 training offers -> B2, else M1",
            "postProcessing": "sorted([p25,p50,p75]) before the decision band "
                              "(~0.023% of M1 predictions showed quantile crossing in validation)",
        },
        "featureSchema": FEATURES,
        "categoricalFeatures": CATEGORICAL,
        "excludedFeatures": {
            "occ": "PromptCloud value is room advertised capacity; LiteAPI adultCount "
                   "is requested party size. Different quantities - cannot be served.",
            "currentPrice": "never a model feature; used only for comparison after prediction",
            "price_rank/default_rank": "rank fields, not prices",
        },
        "taxonomies": {"room": list(ROOM_CATEGORIES),
                       "breakfast": list(BREAKFAST_CATEGORIES),
                       "cancellation": list(CANCELLATION_CATEGORIES)},
        "businessTolerance": BUSINESS_TOLERANCE,
        "decisionBand": "low=min(p25, 0.85*p50); high=max(p75, 1.15*p50)",
        "minHotelObs": MIN_HOTEL_OBS,
        "provenance": {
            "source": "PromptCloud 'Travel & Hotel Listing from Booking.com 2020' (Kaggle, CC0)",
            "file": "marketing_sample_for_booking_com-travel_n_hotel_listing_from_booking_com"
                    "__20200301_20200331__30k_data.json",
            "kaggleSlug": "promptcloud/travel-hotel-listing-from-bookingcom-2020",
            "zipSha256": sha,
            "sourceRecords": 29988,
        },
        "trainingMarket": "IN",
        "trainingCurrency": "INR",
        "currencyBasis": "PROTOTYPE ASSUMPTION - no currency field in source; "
                         "100% of pageurls are booking.com/hotel/in/ and magnitudes match INR",
        "comparisonBasis": "PER_NIGHT_1ROOM_2ADULTS",
        "trainingContext": {"rooms": 1, "adults": 2, "children": 0, "nights": 1,
                            "evidence": "all 29,988 pageurls carry checkout-checkin=1 night, "
                                        "no_rooms=1, group_adults=2, group_children=0"},
        "trainingRows": int(len(d)),
        "trainingHotelKeys": int(d.hotel_key.nunique()),
        "crawlDateRange": [str(d.cr.min().date()), str(d.cr.max().date())],
        "checkinDateRange": [str(d.ci.min().date()), str(d.ci.max().date())],
        "leadTimeRange": [int(d.lead_time_days.min()), int(d.lead_time_days.max())],
        "priceQuantiles": {"p25": float(d.price.quantile(.25)),
                           "p50": float(d.price.median()),
                           "p75": float(d.price.quantile(.75))},
        "validationSummary": val_summary,
        "env": {"python": sys.version.split()[0], "catboost": catboost.__version__,
                "pandas": pd.__version__, "numpy": np.__version__},
    }

    joblib.dump({
        "metadata": meta,
        "b2_hotel_room": hr.reset_index(),
        "b2_hotel": h.reset_index(),
        "known_hotel_keys": sorted(known_keys),
        "ambiguous_hotel_keys": sorted(ambiguous),
        "hotel_obs_counts": counts[counts >= MIN_HOTEL_OBS].to_dict(),
        "global_quantiles": {"p25": float(d.price.quantile(.25)),
                             "p50": float(d.price.median()),
                             "p75": float(d.price.quantile(.75))},
    }, META)
    print(f"[meta] saved {META.name} ({META.stat().st_size/1e6:.2f} MB)")

    back = joblib.load(META)
    m2 = CatBoostRegressor()
    m2.load_model(str(CBM))
    sample = d[FEATURES].head(200)
    assert np.allclose(m.predict(sample), m2.predict(sample)), "reload mismatch"
    print(f"[verify] reload OK, predictions identical on 200 rows")
    print(f"[verify] known keys {len(back['known_hotel_keys']):,} | "
          f"ambiguous {len(back['ambiguous_hotel_keys']):,}")
    print(f"[verify] V1/Osaka artifacts untouched: "
          f"{(ROOT/'models'/'hotel_price_baseline.joblib').exists()} / "
          f"{(ROOT/'models'/'hotel_price_v2_osaka_tiers.joblib').exists()}")


if __name__ == "__main__":
    main()
