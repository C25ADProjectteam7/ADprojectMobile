# Hotel Price V3 M2 Training

Training entry point: **`train_m2.py`**

```bash
.venv/bin/python training/v2mvp_india/experiments/liteapi_v3/train_m2.py
.venv/bin/python training/v2mvp_india/experiments/liteapi_v3/train_m2.py --compare-frozen
```

Pipeline:

```
TRAIN -> CatBoost fitting
CAL   -> conformal quantile calibration
TEST  -> final evaluation
```

---

## 1. Dataset

LiteAPI-native collection across 12 Indian markets, gathered by
`collect_pilot.py` then `collect_full.py`.

Every row is one offer under a fixed contract:

| | |
|---|---|
| stay | 1 night |
| occupancy | 1 room, 2 adults, 0 children |
| currency | INR |
| guest nationality | IN |
| offer selection | cheapest comparable |
| lead times | 1–14 days, single booking date |

| file | rows | hotels | role |
|---|---|---|---|
| `canonical.parquet` | 18,882 | 852 | TRAIN |
| `cal_canonical.parquet` | 2,888 | 150 | CAL |
| `test_canonical.parquet` | 3,228 | 173 | TEST |

Target column: `price` — the observed comparable one-night INR price.

Dataset integrity is asserted at run time: `canonical.parquet` must hash to
`ae859754c2f561b4524d08c7d80888816fe4caf262ddb57345a84f1c036e9dce`, the value
recorded in `reference/m2_frozen_spec.json`.

The `.parquet` files are not kept in the repository. Obtain them from the
collection step before running the training script.

## 2. Feature Engineering

13 features, 6 of them categorical. The order is part of the contract — the
serving path in `ml/india_serving_features.py` builds rows the same way.

```
market, star_bucket, stars_num, room_category, board_category,
cancellation_category, leadTimeDays, guest_rating, has_guest_rating,
log_review_count, chain, hotelTypeId, n_facilities
```

Categorical: `market`, `star_bucket`, `room_category`, `board_category`,
`cancellation_category`, `chain`.

Derivations and transforms:

| feature | rule |
|---|---|
| `star_bucket` | `UNRATED` / `1-2` / `3` / `4` / `5` |
| `stars_num` | star class, `-1` when null or 0 |
| `guest_rating` | rating, `NaN` when null or 0 |
| `has_guest_rating` | flag carrying that fact |
| `log_review_count` | `log1p(reviewCount)`, `NaN` when null or 0 |
| `n_facilities` | `len(facilityIds)` |
| `chain` | `"NA"` when null or empty |

Missing-value semantics live in one place, so training and serving cannot drift
apart.

Deliberately excluded: `hotelId` (exact identity is meaningless for an unseen
hotel), any current or live price (never a feature — it is compared against the
prediction afterwards), the raw 482-dimensional `facilityIds` vector (only the
count is kept; facilities added ~2% incremental signal over star class), and any
price-derived rank.

## 3. CatBoost Model

- CatBoost MultiQuantile regression
- P25 / P50 / P75 prediction
- iterations: 500
- depth: 6
- learning_rate: 0.1
- random_seed: 20260815

```python
CatBoostRegressor(
    loss_function="MultiQuantile:alpha=0.25,0.5,0.75",
    iterations=500,
    depth=6,
    learning_rate=0.1,
    random_seed=20260815,
    verbose=0,
    thread_count=-1,
)
```

**Hotel-balanced row weights.** Each row is weighted `1 / rows-for-that-hotel`,
so every hotel contributes the same total weight and one property with 50
collected offers cannot outvote ten properties with 5. The same weighting is
used for every reported metric.

```python
TR["w"] = 1.0 / TR.groupby("hotelId").hotelId.transform("size")
```

Quantile crossing is possible in MultiQuantile output, so predictions are sorted
per row before any band is formed — the same anti-crossing step the serving path
applies.

## 4. TRAIN / CAL / TEST split

The split is on **whole hotels**, not rows. The model's job is to price a hotel
it has never seen; a row-level split would leak the same property into both
sides and flatter the result. All three `hotelId` sets are asserted mutually
disjoint at run time.

Split membership comes from the recorded artifacts, never re-sampled:

- CAL and TEST membership is read from `cal_canonical.parquet` and
  `test_canonical.parquet`, and asserted to be a subset of the candidate pool in
  `reference/caltest_manifests.json`.
- `reference/excluded_hotel_ids.json` is the exclusion list applied when
  sampling CAL/TEST candidates. It is not a TRAIN filter, so the script asserts
  that no excluded hotel reached CAL or TEST rather than applying it as a
  filter.
- `reference/split_manifest.json` holds the 5-fold cross-validation assignment
  used for model selection in `run_baseline_first.py`. It is not the final
  split.

Only TRAIN participates in `fit()`.

## 5. Calibration

CAL is used only after fitting, for conformalized quantile regression with a
normalized score:

```
E    = max(qlo - y, y - qhi) / (qhi - qlo)
qhat = hotel-balanced 50th percentile of E over the CAL hotels
```

**qhat = 0.420727990504081** (`reference/calibration.json`). The script computes
this from CAL rather than hardcoding it, and reports the difference against the
recorded value.

At serving time the interval is widened at the endpoints only —
`p25 - qhat·width`, `p75 + qhat·width` — so P50 is untouched by construction.

This is **group-disjoint empirical** calibration. CAL hotels are permanently
excluded from fitting, but rows cluster within hotels, so row-level
exchangeability does not hold and no formal marginal coverage guarantee is
claimed. Calibrated CAL coverage is 0.499 against a 0.50 nominal.

## 6. Evaluation

TEST is a single untouched hold-out. It takes no part in fitting, model
selection or calibration, and is read once after the model is locked.

| | |
|---|---|
| Baseline MdAPE | **33.5%** |
| V3 M2 MdAPE | **28.9%** |
| Relative error reduction | **13.8%** |

Supporting TEST figures: pinball 1097.3 (baseline 1219.1), interval coverage
0.329 raw and 0.549 calibrated, median actual/predicted P50 0.98.

MdAPE is a *median absolute percentage error* — an error measure, reported as
such.

`--compare-frozen` additionally scores `reference/m2_final.cbm`, the frozen
reference model, over the same feature matrices and reports the qhat difference,
the per-quantile prediction differences and the metric differences.

## 7. Output artifacts

`train_m2.py` writes:

```
reference/m2_trained.cbm       trained model
reference/m2_trained.joblib    run report: params, splits, qhat, metrics, hashes
```

It never overwrites `reference/m2_final.cbm` (the frozen reference model) or
`models/hotel_price_india_v3_m2.cbm` (production).

Note that a saved `.cbm` embeds a freshly generated `model_guid` and the
training date, so two runs producing identical predictions still yield different
file hashes. Compare predictions and leaf values, not SHA256.

Promotion to production is a separate, explicit step:
`training/v2mvp_india/build_artifact_v3.py`. That script performs **no refit** —
it copies the frozen binary, asserts its SHA256, carries the historical B2
tables over verbatim from the v2.1 bundle, and packages the serving metadata
(feature schema, qhat, temporal factors, decision band, TEST metrics).
