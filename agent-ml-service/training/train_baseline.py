"""
First reproducible baseline — Hotel Booking Demand (Antonio, Almeida & Nunes, 2019)
====================================================================================
Pipeline: raw CSV -> cleaning -> feature selection -> preprocessing
          -> time-based train/val/test split -> baseline models -> evaluation

V1 scope decision (see docs/ml/hotel-price-dataset-shortlist.md):
  city and hotel_star_rating are NOT in this dataset and are NOT used as
  features here. This model predicts ADR from booking/stay characteristics
  only (lead time, season, stay length, guests, room-type code, channel).

Run: .venv/bin/python training/train_baseline.py
"""
from pathlib import Path

import joblib
import pandas as pd
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import RandomForestRegressor
from sklearn.linear_model import LinearRegression
from sklearn.metrics import mean_absolute_error, mean_squared_error
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder

MODEL_ARTIFACT_PATH = Path(__file__).resolve().parent.parent / "models" / "hotel_price_baseline.joblib"

MONTH_MAP = {
    "January": 1, "February": 2, "March": 3, "April": 4, "May": 5, "June": 6,
    "July": 7, "August": 8, "September": 9, "October": 10, "November": 11, "December": 12,
}

NUMERIC_FEATURES = ["lead_time", "nights", "number_of_guests", "arrival_month_num"]
CATEGORICAL_FEATURES = ["hotel", "reserved_room_type", "market_segment", "deposit_type", "customer_type"]
TARGET = "adr"


def load_and_clean(path: str) -> pd.DataFrame:
    df = pd.read_csv(path)
    n0 = len(df)

    # Decision: drop canceled bookings — the authors note cancel/non-cancel
    # distributions differ, and we're predicting the price of a real stay.
    df = df[df["is_canceled"] == 0].copy()
    n1 = len(df)

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

    # Decision: drop adr<=0 and cap the top 1% as outliers — keeps the
    # first baseline simple; comp/negative rows and extreme luxury spikes
    # are not what a v1 mock-replacement needs to get right.
    df = df[df["adr"] > 0]
    cap = df["adr"].quantile(0.99)
    df = df[df["adr"] <= cap]
    n2 = len(df)

    df = df.dropna(subset=NUMERIC_FEATURES + CATEGORICAL_FEATURES + [TARGET])
    n3 = len(df)

    print(f"[clean] raw={n0} -> after cancel filter={n1} -> after date/nights/guest/adr filter={n2} -> after final dropna={n3}")
    return df


def time_based_split(df: pd.DataFrame):
    df = df.sort_values("arrival_date").reset_index(drop=True)
    n = len(df)
    train_end = int(n * 0.70)
    val_end = int(n * 0.85)
    train, val, test = df.iloc[:train_end], df.iloc[train_end:val_end], df.iloc[val_end:]
    print(f"[split] train={len(train)} ({train['arrival_date'].min().date()}..{train['arrival_date'].max().date()}) "
          f"val={len(val)} ({val['arrival_date'].min().date()}..{val['arrival_date'].max().date()}) "
          f"test={len(test)} ({test['arrival_date'].min().date()}..{test['arrival_date'].max().date()})")
    return train, val, test


def build_pipeline(model) -> Pipeline:
    preprocess = ColumnTransformer([
        ("num", "passthrough", NUMERIC_FEATURES),
        ("cat", OneHotEncoder(handle_unknown="ignore"), CATEGORICAL_FEATURES),
    ])
    return Pipeline([("preprocess", preprocess), ("model", model)])


def evaluate(pipeline: Pipeline, X, y, label: str):
    pred = pipeline.predict(X)
    mae = mean_absolute_error(y, pred)
    rmse = mean_squared_error(y, pred) ** 0.5
    mape = (( (y - pred).abs() / y ).mean()) * 100
    print(f"[eval] {label:28s} MAE={mae:6.2f}  RMSE={rmse:6.2f}  MAPE={mape:5.2f}%")
    return {"mae": mae, "rmse": rmse, "mape": mape}


def main():
    df = load_and_clean("training/data/hotel_bookings.csv")
    train, val, test = time_based_split(df)

    X_train, y_train = train[NUMERIC_FEATURES + CATEGORICAL_FEATURES], train[TARGET]
    X_val, y_val = val[NUMERIC_FEATURES + CATEGORICAL_FEATURES], val[TARGET]
    X_test, y_test = test[NUMERIC_FEATURES + CATEGORICAL_FEATURES], test[TARGET]

    print("\n=== Baseline 1: Linear Regression ===")
    lr = build_pipeline(LinearRegression())
    lr.fit(X_train, y_train)
    evaluate(lr, X_train, y_train, "LinearRegression (train)")
    evaluate(lr, X_val, y_val, "LinearRegression (val)")

    print("\n=== Baseline 2: Random Forest ===")
    # n_estimators/max_depth chosen for artifact size (66.9MB -> 4.5MB at
    # n_estimators=60/max_depth=10/min_samples_leaf=10), not accuracy tuning —
    # val MAE was actually marginally better at this smaller size (17.93 vs 18.21).
    rf = build_pipeline(RandomForestRegressor(n_estimators=60, max_depth=10, min_samples_leaf=10, random_state=42, n_jobs=-1))
    rf.fit(X_train, y_train)
    evaluate(rf, X_train, y_train, "RandomForest (train)")
    evaluate(rf, X_val, y_val, "RandomForest (val)")

    print("\n=== Final test-set evaluation (Random Forest — better val score) ===")
    evaluate(rf, X_test, y_test, "RandomForest (test)")

    # Save exactly the pipeline that was evaluated above — preprocessing
    # (ColumnTransformer: passthrough numeric + OneHotEncoder categorical)
    # and the fitted RandomForestRegressor together, as one object, so
    # inference at serve time uses the identical transformations as training.
    # Not refit on train+val — this artifact matches the metrics reported.
    MODEL_ARTIFACT_PATH.parent.mkdir(parents=True, exist_ok=True)
    joblib.dump(rf, MODEL_ARTIFACT_PATH)
    size_kb = MODEL_ARTIFACT_PATH.stat().st_size / 1024
    print(f"\n[artifact] saved {MODEL_ARTIFACT_PATH} ({size_kb:.1f} KB)")
    print(f"[artifact] expected input columns: {NUMERIC_FEATURES + CATEGORICAL_FEATURES}")
    print(f"[artifact] target: {TARGET}")


if __name__ == "__main__":
    main()
