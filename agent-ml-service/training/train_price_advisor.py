"""
Price Advisor — quantile regression on the Hotel Booking Demand dataset
========================================================================
Trains THREE quantile models (P25 / P50 / P75) so the API can answer:

  1. "What price RANGE should I expect for this stay?"   -> P25..P75
  2. "WHEN should I book to get the cheapest price?"     -> lead-time curve
  3. "Which MONTH is cheapest for this trip?"            -> arrival-month curve

Unlike the v1 baseline (single point estimate), quantile loss gives a
genuine interval, and scanning lead_time/arrival_month through the fitted
models produces model-driven timing advice — no external price scraping.

Data: Hotel Booking Demand (Antonio, Almeida & Nunes, 2019), same cleaning
as train_baseline.py so the two artifacts stay comparable.

Run:  python training/train_price_advisor.py
"""
from pathlib import Path

import joblib
import pandas as pd
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import HistGradientBoostingRegressor
from sklearn.metrics import mean_absolute_error
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder

MODEL_ARTIFACT_PATH = Path(__file__).resolve().parent.parent / "models" / "price_advisor_v1.joblib"

MONTH_MAP = {
    "January": 1, "February": 2, "March": 3, "April": 4, "May": 5, "June": 6,
    "July": 7, "August": 8, "September": 9, "October": 10, "November": 11, "December": 12,
}

NUMERIC_FEATURES = ["lead_time", "nights", "number_of_guests", "arrival_month_num"]
CATEGORICAL_FEATURES = ["hotel", "reserved_room_type", "market_segment", "deposit_type", "customer_type", "adr_tier"]
TARGET = "adr"

# Price bands for the adr_tier feature: hotels within the same band share
# pricing patterns (lead-time curves, seasonality). At serve time the band
# comes from the hotel's CURRENT rate, so advice compares against the same
# band's market range instead of a city-wide average (a $360 Manhattan hotel
# must not be judged against an $88 average).
TIER_EDGES = [0.0, 100.0, 200.0, float("inf")]
TIER_LABELS = ["economy", "mid", "premium"]

QUANTILES = [0.25, 0.5, 0.75]


def load_and_clean(path: str) -> pd.DataFrame:
    """Same cleaning as train_baseline.py — see its docstring for the
    rationale behind each filter."""
    df = pd.read_csv(path)
    df = df[df["is_canceled"] == 0].copy()
    df["arrival_month_num"] = df["arrival_date_month"].map(MONTH_MAP)
    df["arrival_date"] = pd.to_datetime(
        df[["arrival_date_year", "arrival_month_num", "arrival_date_day_of_month"]]
        .rename(columns={"arrival_date_year": "year", "arrival_month_num": "month", "arrival_date_day_of_month": "day"}),
        errors="coerce",
    )
    df = df.dropna(subset=["arrival_date"])
    df["nights"] = df["stays_in_weekend_nights"] + df["stays_in_week_nights"]
    df = df[df["nights"] > 0]
    df["children"] = df["children"].fillna(0)
    df["number_of_guests"] = df["adults"] + df["children"] + df["babies"]
    df = df[df["number_of_guests"] > 0]
    df = df[df["adr"] > 0]
    cap = df["adr"].quantile(0.99)
    df = df[df["adr"] <= cap]
    df["adr_tier"] = pd.cut(df["adr"], bins=TIER_EDGES, labels=TIER_LABELS)
    df = df.dropna(subset=NUMERIC_FEATURES + CATEGORICAL_FEATURES + [TARGET])
    return df


def time_based_split(df: pd.DataFrame):
    df = df.sort_values("arrival_date").reset_index(drop=True)
    n = len(df)
    train_end = int(n * 0.70)
    val_end = int(n * 0.85)
    return df.iloc[:train_end], df.iloc[train_end:val_end], df.iloc[val_end:]


def build_pipeline(quantile: float) -> Pipeline:
    """One gradient-boosting pipeline per quantile. HistGradientBoosting
    natively supports quantile loss, so no extra dependency (CatBoost) and
    artifacts stay small (~1-2MB each)."""
    preprocess = ColumnTransformer([
        ("num", "passthrough", NUMERIC_FEATURES),
        ("cat", OneHotEncoder(handle_unknown="ignore"), CATEGORICAL_FEATURES),
    ])
    model = HistGradientBoostingRegressor(
        loss="quantile",
        quantile=quantile,
        max_iter=400,
        learning_rate=0.08,
        max_depth=8,
        min_samples_leaf=25,
        random_state=42,
    )
    return Pipeline([("preprocess", preprocess), ("model", model)])


def evaluate_intervals(pipelines: dict, X, y, label: str):
    """Median MAE plus P25-P75 interval coverage (ideal ~50% of rows inside)."""
    p25 = pipelines[0.25].predict(X)
    p50 = pipelines[0.5].predict(X)
    p75 = pipelines[0.75].predict(X)
    mae = mean_absolute_error(y, p50)
    coverage = float(((y >= p25) & (y <= p75)).mean()) * 100
    print(f"[eval] {label:22s} median MAE={mae:6.2f}  P25-P75 coverage={coverage:5.1f}%")
    return {"mae": mae, "coverage": coverage}


def main():
    df = load_and_clean("training/data/hotel_bookings.csv")
    train, val, test = time_based_split(df)
    print(f"[data] cleaned rows={len(df)}  train={len(train)}  val={len(val)}  test={len(test)}")

    feature_cols = NUMERIC_FEATURES + CATEGORICAL_FEATURES
    X_train, y_train = train[feature_cols], train[TARGET]
    X_val, y_val = val[feature_cols], val[TARGET]
    X_test, y_test = test[feature_cols], test[TARGET]

    pipelines = {}
    for q in QUANTILES:
        print(f"\n=== Training quantile {q} ===")
        pipe = build_pipeline(q)
        pipe.fit(X_train, y_train)
        pipelines[q] = pipe

    print("\n=== Evaluation ===")
    for label, (X, y) in (("train", (X_train, y_train)), ("val", (X_val, y_val)), ("test", (X_test, y_test))):
        evaluate_intervals(pipelines, X, y, label)

    MODEL_ARTIFACT_PATH.parent.mkdir(parents=True, exist_ok=True)
    joblib.dump({
        "models": pipelines,
        "numeric_features": NUMERIC_FEATURES,
        "categorical_features": CATEGORICAL_FEATURES,
        "feature_columns": feature_cols,
        "quantiles": QUANTILES,
        "target": TARGET,
        "metadata": {
            "modelVersion": "lead-time-quantile-v2",
            "modelStatus": "trained",
            "trainedRows": len(train),
            "dataset": "Hotel Booking Demand (Antonio, Almeida & Nunes, 2019)",
            "tierEdges": TIER_EDGES,
            "tierLabels": TIER_LABELS,
            "note": "city / hotel_star_rating are NOT features (dataset has none); "
                    "advice is driven by lead time, month, stay length, guests and "
                    "the hotel's price band (economy/mid/premium).",
        },
    }, MODEL_ARTIFACT_PATH)
    size_mb = MODEL_ARTIFACT_PATH.stat().st_size / 1024 / 1024
    print(f"\n[artifact] saved {MODEL_ARTIFACT_PATH} ({size_mb:.1f} MB)")


if __name__ == "__main__":
    main()
