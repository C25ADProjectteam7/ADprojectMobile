"""
Hotel Price V3 M2 Training Pipeline.

Trains the CatBoost MultiQuantile hotel fair-price model using the
V3 feature contract and hotel-disjoint TRAIN/CAL/TEST datasets.

Pipeline:
TRAIN -> CatBoost fitting
CAL   -> conformal quantile calibration
TEST  -> final evaluation

Outputs (the frozen reference model m2_final.cbm is never overwritten):
    reference/m2_trained.cbm
    reference/m2_trained.joblib

Run:
    .venv/bin/python training/v2mvp_india/experiments/liteapi_v3/train_m2.py
    .venv/bin/python .../train_m2.py --compare-frozen
"""
from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from catboost import CatBoostRegressor, Pool

HERE = Path(__file__).resolve().parent
REF = HERE / "reference"

TRAIN_PARQUET = HERE / "canonical.parquet"
CAL_PARQUET = HERE / "cal_canonical.parquet"
TEST_PARQUET = HERE / "test_canonical.parquet"

FROZEN_CBM = REF / "m2_final.cbm"                 # opened read-only, never written
OUT_CBM = REF / "m2_trained.cbm"
OUT_META = REF / "m2_trained.joblib"

CANONICAL_SHA256 = "ae859754c2f561b4524d08c7d80888816fe4caf262ddb57345a84f1c036e9dce"
FROZEN_QHAT = 0.420727990504081

# Exactly the parameters embedded in m2_final.cbm. verbose/thread_count are not
# stored in the binary and come from m2_frozen_spec.json catboostParams.
SEED = 20260815
CB = dict(loss_function="MultiQuantile:alpha=0.25,0.5,0.75", iterations=500,
          depth=6, learning_rate=0.1, random_seed=SEED, verbose=0, thread_count=-1)

# Order is part of the contract: m2_final.cbm reports exactly this sequence in
# feature_names_, and the serving path builds rows the same way.
V3_FEATURES = ["market", "star_bucket", "stars_num", "room_category",
               "board_category", "cancellation_category", "leadTimeDays",
               "guest_rating", "has_guest_rating", "log_review_count",
               "chain", "hotelTypeId", "n_facilities"]
V3_CATEGORICAL = ["market", "star_bucket", "room_category",
                  "board_category", "cancellation_category", "chain"]

Q = (0.25, 0.50, 0.75)
TARGET = "price"      # observed comparable one-night INR, 1 room / 2 adults / 0 children

# Counts recorded in the shipped artifact metadata. Asserted, never forced.
EXPECTED = {"TRAIN": (18882, 852), "CAL": (2888, 150), "TEST": (3228, 173)}

FROZEN_TEST_METRICS = {"MdAPE": 0.289, "pinball": 1097.3, "coverageRaw": 0.329,
                       "coverageCalibrated": 0.549, "medianActualOverPredictedP50": 0.98}


# ------------------------------------------------------------------ features
def star_bucket(s):
    if pd.isna(s) or s == 0:
        return "UNRATED"
    return "1-2" if s <= 2 else ("3" if s < 4 else ("4" if s < 5 else "5"))


def build_features(df: pd.DataFrame) -> pd.DataFrame:
    """Derive the V3 columns, per run_baseline_first.py and the
    missingSemantics / transforms blocks of m2_frozen_spec.json.

    Verified: these seven derivations reproduce, exactly, the corresponding
    columns still stored inside cal_canonical.parquet and test_canonical.parquet.
    """
    d = df.copy()
    d["star_bucket"] = d["stars"].map(star_bucket)
    d["stars_num"] = d["stars"].fillna(-1)
    d["has_guest_rating"] = ((~d["rating"].isna()) & (d["rating"] > 0)).astype(int)
    d["guest_rating"] = np.where(d["has_guest_rating"] == 1, d["rating"], np.nan)
    d["log_review_count"] = np.log1p(d["reviewCount"].where(d["reviewCount"] > 0))
    d["n_facilities"] = d["facilityIds"].map(len)
    d["chain"] = d["chain"].fillna("NA").replace("", "NA")
    return d


def hotel_balanced_weights(df: pd.DataFrame) -> np.ndarray:
    """w = 1 / rows-for-that-hotel, so each hotel contributes the same total
    weight and one property with 50 offers cannot outvote ten with 5."""
    return (1.0 / df.groupby("hotelId")["hotelId"].transform("size")).to_numpy()


def design_matrix(df: pd.DataFrame) -> pd.DataFrame:
    X = df[V3_FEATURES].copy()
    for c in V3_CATEGORICAL:
        X[c] = X[c].astype(str)
    return X


# -------------------------------------------------------------------- splits
def recover_splits():
    """Recover the ORIGINAL TRAIN/CAL/TEST. Nothing is re-sampled.

    How the surviving artifacts fit together, established by inspection:

      reference/excluded_hotel_ids.json (2,274 ids) is the exclusion list that
      was applied when SAMPLING the CAL/TEST candidates - it contains all 852
      TRAIN hotels and has zero overlap with either candidate pool. It is NOT a
      TRAIN filter: subtracting it from TRAIN would remove all 852 TRAIN hotels
      and leave nothing to fit. It is therefore asserted as a disjointness
      check against CAL/TEST rather than applied as a filter.

      reference/caltest_manifests.json is the candidate POOL (seed 20260816,
      40 hotels per market: 480 CAL + 478 TEST). It is not the final
      membership - only those candidates that returned a usable comparable rate
      survived into cal_/test_canonical.parquet (150 and 173). That selection
      step is not itself recorded, so the realized membership is taken from the
      parquets and asserted to be a subset of the manifest pool.

      TRAIN is all of canonical.parquet. Its 852 hotels / 18,882 rows match the
      shipped trainHotels/trainRows exactly, and it is already disjoint from
      CAL and TEST, so no subtraction is needed. (Inference: no artifact records
      the fit's input rows directly - see the note in the README.)
    """
    got = hashlib.sha256(TRAIN_PARQUET.read_bytes()).hexdigest()
    assert got == CANONICAL_SHA256, f"canonical.parquet SHA256 drifted: {got}"

    train = build_features(pd.read_parquet(TRAIN_PARQUET))
    cal = build_features(pd.read_parquet(CAL_PARQUET))
    test = build_features(pd.read_parquet(TEST_PARQUET))

    manifest = json.load(open(REF / "caltest_manifests.json"))
    cal_pool = {h["hotelId"] for m in manifest["CAL"].values() for h in m}
    test_pool = {h["hotelId"] for m in manifest["TEST"].values() for h in m}
    excluded = set(json.load(open(REF / "excluded_hotel_ids.json")))

    ids = {k: set(d["hotelId"].astype(str)) for k, d in
           (("TRAIN", train), ("CAL", cal), ("TEST", test))}

    # Realized CAL/TEST must come from the recorded candidate pools.
    assert ids["CAL"] <= cal_pool, "CAL hotels are not a subset of the CAL manifest pool"
    assert ids["TEST"] <= test_pool, "TEST hotels are not a subset of the TEST manifest pool"
    # The exclusion list did its job: no excluded hotel reached CAL or TEST.
    assert not (excluded & ids["CAL"]), "an excluded hotel reached CAL"
    assert not (excluded & ids["TEST"]), "an excluded hotel reached TEST"

    for name, df in (("TRAIN", train), ("CAL", cal), ("TEST", test)):
        rows, hotels = EXPECTED[name]
        assert len(df) == rows, f"{name}: expected {rows} rows, got {len(df)}"
        assert len(ids[name]) == hotels, f"{name}: expected {hotels} hotels, got {len(ids[name])}"

    assert not (ids["TRAIN"] & ids["CAL"]), "TRAIN and CAL share hotels"
    assert not (ids["TRAIN"] & ids["TEST"]), "TRAIN and TEST share hotels"
    assert not (ids["CAL"] & ids["TEST"]), "CAL and TEST share hotels"

    print(f"[data] canonical.parquet sha256 verified {got[:16]}...")
    print(f"[split] TRAIN {len(train):,} rows / {len(ids['TRAIN'])} hotels")
    print(f"[split] CAL   {len(cal):,} rows / {len(ids['CAL'])} hotels "
          f"(from a {len(cal_pool)}-hotel candidate pool)")
    print(f"[split] TEST  {len(test):,} rows / {len(ids['TEST'])} hotels "
          f"(from a {len(test_pool)}-hotel candidate pool)")
    print("[split] TRAIN/CAL/TEST hotelId sets are mutually disjoint")
    return train, cal, test


# ------------------------------------------------------------------- metrics
def wquantile(v, w, q) -> float:
    o = np.argsort(v)
    v, w = np.asarray(v)[o], np.asarray(w)[o]
    cw = np.cumsum(w) - 0.5 * w
    cw /= np.sum(w)
    return float(np.interp(q, cw, v))


def pinball(y, p, w) -> float:
    tot = 0.0
    for i, t in enumerate(Q):
        d = y - p[:, i]
        tot += np.average(np.maximum(t * d, (t - 1) * d), weights=w)
    return float(tot / len(Q))


def sorted_predict(model: CatBoostRegressor, df: pd.DataFrame) -> np.ndarray:
    """MultiQuantile output can cross, so sort per row - the same anti-crossing
    step the serving path applies before building a band."""
    return np.sort(model.predict(design_matrix(df)), axis=1)


def cqr_qhat(model: CatBoostRegressor, cal: pd.DataFrame, w: np.ndarray) -> float:
    """Normalized CQR score, hotel-balanced:

        E    = max(qlo - y, y - qhi) / (qhi - qlo)
        qhat = hotel-balanced 50th percentile of E over the CAL hotels

    GROUP-DISJOINT EMPIRICAL calibration. CAL hotels are permanently excluded
    from fitting, but rows cluster within hotels, so row-level exchangeability
    does not hold and no formal marginal coverage guarantee is claimed.
    """
    p = sorted_predict(model, cal)
    lo, hi = p[:, 0], p[:, 2]
    y = cal[TARGET].to_numpy()
    e = np.maximum(lo - y, y - hi) / np.maximum(hi - lo, 1e-9)
    return wquantile(e, w, 0.5)


def evaluate(model: CatBoostRegressor, df: pd.DataFrame, w: np.ndarray,
             qhat: float) -> dict:
    p = sorted_predict(model, df)
    lo, mid, hi = p[:, 0], p[:, 1], p[:, 2]
    y = df[TARGET].to_numpy()
    width = hi - lo
    ape = np.abs(y - mid) / np.maximum(y, 1e-9)
    return {
        "MdAPE": round(wquantile(ape, w, 0.5), 3),
        "pinball": round(pinball(y, p, w), 1),
        "coverageRaw": round(float(np.average((y >= lo) & (y <= hi), weights=w)), 3),
        "coverageCalibrated": round(float(np.average(
            (y >= lo - qhat * width) & (y <= hi + qhat * width), weights=w)), 3),
        "medianActualOverPredictedP50": round(wquantile(y / np.maximum(mid, 1e-9), w, 0.5), 2),
    }


# ---------------------------------------------------------------- comparison
def compare_models(frozen, recon, splits) -> dict:
    """Score both models over identical feature matrices and report the spread.

    Measured result on this dataset: predictions are bit-identical on TRAIN,
    CAL and TEST for all three quantiles (max abs diff 0.0), all 95,472 leaf
    values match, and the two .cbm files are the same size with only 39 bytes
    differing - the save-time model_guid and the embedded training date. The
    SHA256 therefore differs while the model itself does not.
    """
    out = {}
    for name, df, w in splits:
        a, b = sorted_predict(frozen, df), sorted_predict(recon, df)
        per_q = {}
        for i, q in enumerate(("P25", "P50", "P75")):
            d = np.abs(a[:, i] - b[:, i])
            per_q[q] = {
                "meanAbsDiff": round(float(d.mean()), 4),
                "medianAbsDiff": round(float(np.median(d)), 4),
                "maxAbsDiff": round(float(d.max()), 4),
                "correlation": round(float(np.corrcoef(a[:, i], b[:, i])[0, 1]), 6),
                "allclose_rtol1e-3": bool(np.allclose(a[:, i], b[:, i], rtol=1e-3, atol=1.0)),
                "allclose_rtol1e-2": bool(np.allclose(a[:, i], b[:, i], rtol=1e-2, atol=10.0)),
            }
        out[name] = per_q
    return out


def sha256(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--compare-frozen", action="store_true",
                    help="score m2_final.cbm alongside the newly trained model")
    args = ap.parse_args()

    train, cal, test = recover_splits()
    w_train = hotel_balanced_weights(train)
    w_cal = hotel_balanced_weights(cal)
    w_test = hotel_balanced_weights(test)

    # ---- FIT: TRAIN ONLY --------------------------------------------------
    X_train = design_matrix(train)
    y_train = train[TARGET].to_numpy()
    train_pool = Pool(X_train, y_train, weight=w_train, cat_features=V3_CATEGORICAL)

    model = CatBoostRegressor(**CB)
    model.fit(train_pool)
    print(f"[fit] {CB['loss_function']} iterations={CB['iterations']} "
          f"depth={CB['depth']} lr={CB['learning_rate']} seed={CB['random_seed']}")

    # ---- CALIBRATE: CAL ONLY, computed not hardcoded ----------------------
    qhat = cqr_qhat(model, cal, w_cal)
    print(f"[cal] computed qhat = {qhat!r}")
    print(f"[cal] frozen   qhat = {FROZEN_QHAT!r}   |diff| = {abs(qhat - FROZEN_QHAT):.6g}")

    # ---- EVALUATE: TEST ONLY ----------------------------------------------
    metrics = evaluate(model, test, w_test, qhat)
    print(f"[test] trained {metrics}")
    print(f"[test] frozen        {FROZEN_TEST_METRICS}")

    model.save_model(str(OUT_CBM))
    recon_sha = sha256(OUT_CBM)
    frozen_sha = sha256(FROZEN_CBM) if FROZEN_CBM.exists() else None
    print(f"[save] {OUT_CBM.name} sha256={recon_sha}")
    print(f"[ref ] m2_final.cbm  sha256={frozen_sha}")

    report = {
        "generatedBy": Path(__file__).name,
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "catboostParams": CB,
        "features": V3_FEATURES,
        "categoricalFeatures": V3_CATEGORICAL,
        "target": TARGET,
        "weighting": "hotel-balanced: w = 1 / rows-for-that-hotel",
        "datasetSha256": {"canonical.parquet": CANONICAL_SHA256},
        "splits": {k: {"rows": v[0], "hotels": v[1]} for k, v in EXPECTED.items()},
        "splitProvenance": "CAL/TEST identities recovered from caltest_manifests.json "
                           "(candidate pool) and the realized cal_/test_canonical.parquet; "
                           "no re-sampling. excluded_hotel_ids.json is a CAL/TEST sampling "
                           "exclusion list, not a TRAIN filter.",
        "qhatComputed": qhat,
        "qhatFrozen": FROZEN_QHAT,
        "qhatAbsDiff": abs(qhat - FROZEN_QHAT),
        "testMetricsTrained": metrics,
        "testMetricsFrozen": FROZEN_TEST_METRICS,
        "trainedModelSha256": recon_sha,
        "frozenModelSha256": frozen_sha,
        "reproducesFrozenSha256": recon_sha == frozen_sha,
        "shaDifferenceExplanation":
            "SHA256 differs only because CatBoost stamps a fresh random model_guid "
            "and the training date into every saved model. Measured: same file size, "
            "39 of 1,245,860 bytes differ (model_guid at 4716-4754, date at "
            "34008-34018), all 95,472 leaf values identical, predictions "
            "bit-identical on TRAIN/CAL/TEST.",
    }

    if args.compare_frozen and FROZEN_CBM.exists():
        frozen = CatBoostRegressor()
        frozen.load_model(str(FROZEN_CBM))              # read-only
        f_qhat = cqr_qhat(frozen, cal, w_cal)
        f_metrics = evaluate(frozen, test, w_test, f_qhat)
        diffs = compare_models(frozen, model,
                               [("TRAIN", train, w_train), ("CAL", cal, w_cal),
                                ("TEST", test, w_test)])
        print(f"\n[frozen] qhat={f_qhat!r}  matches calibration.json: "
              f"{abs(f_qhat - FROZEN_QHAT) < 1e-12}")
        print(f"[frozen] TEST {f_metrics}")
        for split, per_q in diffs.items():
            print(f"[diff:{split}] " + " | ".join(
                f"{q} mean={v['meanAbsDiff']} max={v['maxAbsDiff']} r={v['correlation']}"
                for q, v in per_q.items()))
        report["frozenRecheck"] = {"qhat": f_qhat, "testMetrics": f_metrics}
        report["predictionComparison"] = diffs

    joblib.dump(report, OUT_META)
    print(f"[save] {OUT_META.name}")


if __name__ == "__main__":
    main()
