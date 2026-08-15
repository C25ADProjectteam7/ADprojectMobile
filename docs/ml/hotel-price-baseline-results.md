# Hotel Price Baseline — Dataset Finalization & First Model Results

Status (updated 2026-08-11): **baseline trained, saved, and integrated into
the live FastAPI endpoint.** `ml/routes.py` now serves `HotelPricePredictor`
(real inference from `models/hotel_price_baseline.joblib`) instead of
`MockHotelPricePredictor`. See §8 below for exactly what that does and does
not mean.

## 1. Dataset Finalization

**Source:** Hotel Booking Demand (Antonio, Almeida & Nunes, 2019), CC BY
license — the Primary candidate from `docs/ml/hotel-price-dataset-shortlist.md`.
Downloaded from the R4DS TidyTuesday GitHub mirror (plain CSV, no auth) and
verified against the paper: **119,390 rows**, matching the paper's
40,060 (resort) + 79,330 (city) exactly.

**Real inspection findings** (`training/inspect_dataset.py`):
- 32 columns; missing values concentrated in `company` (112,593 missing —
  expected, most bookings aren't corporate), `agent` (16,340), `country` (488),
  `children` (4).
- Target `adr`: mean 101.83, but **1 negative value** and **1,960 rows ≤ 0**,
  max 5,400 against a median of 94.6 — real data-quality issues, not
  hypothetical ones.
- `hotel`: only 2 values (`City Hotel` 79,330 / `Resort Hotel` 40,060) — this
  is the only location signal in the entire dataset.
- No star-rating column anywhere in the 32 columns.
- `reserved_room_type`/`assigned_room_type`: letter codes (A, B, C, D, E, F,
  G, H, P, L / A, B, C, D, E, F, G, H, I, K, P, L) with no documented mapping
  to our `single`/`double`/`twin`/`suite` enum.

**Feature availability vs. `HotelPriceRequest`:**

| API field | Status | Resolution |
|---|---|---|
| `city` | Missing — no column, not derivable | Excluded from V1 features |
| `check_in_date` | Derivable | `arrival_date_year/month/day` → real date |
| `check_out_date` | Derivable | check-in + nights |
| `booking_date` | Derivable | check-in − `lead_time` |
| `hotel_star_rating` | Missing — no column, not derivable | Excluded from V1 features |
| `room_type` | Present, not directly compatible | Used as-is (letter code), not remapped to the API enum |
| `number_of_guests` | Derivable | `adults + children + babies` |
| `currency` | Missing — not stated (assumed EUR) | Not modeled; conflicts with the live API's USD-only restriction |
| target (`adr`) | Present, needs cleaning | See preprocessing below |

**V1 decision: Option A — simplify the feature set, do not combine a second
dataset.** `city` and `hotel_star_rating` are absent from every column, not
just imperfectly documented — a second dataset would be needed to supply
them, which means reconciling different countries/currencies/schemas. That
risk is disproportionate to what a first reproducible baseline needs. This
confirms (with real data, not assumption) the recommendation already made in
`docs/ml/hotel-price-dataset-shortlist.md`.

## 2. Preprocessing (`training/train_baseline.py`)

1. **Drop canceled bookings** (`is_canceled == 1`) — the paper's authors note
   canceled/non-canceled distributions differ; we're predicting the price of
   a real stay, not a hypothetical one.
2. **Derive `arrival_date`** from year/month-name/day.
3. **Derive `nights`** = weekend nights + week nights; drop 0-night rows.
4. **Derive `number_of_guests`** = adults + children (NaN → 0) + babies; drop
   0-guest rows.
5. **Drop `adr <= 0`** and cap at the 99th percentile — removes comp/negative
   rows and extreme luxury outliers for this first pass.
6. Final `dropna` on all selected features + target.

**Row counts through the pipeline:** 119,390 raw → 75,166 after cancellation
filter → 72,654 after date/nights/guest/adr filtering.

## 3. Features & Target

- **Numeric:** `lead_time`, `nights`, `number_of_guests`, `arrival_month_num`
- **Categorical (one-hot encoded):** `hotel`, `reserved_room_type`,
  `market_segment`, `deposit_type`, `customer_type`
- **Target:** `adr` (Average Daily Rate), used as the training proxy for
  `predicted_price_per_night`
- **Explicitly excluded:** `city`, `hotel_star_rating` — do not exist in this
  dataset (see V1 decision above)

## 4. Train / Validation / Test Split

Strictly time-based on `arrival_date`, sorted chronologically, no shuffling:

| Split | Rows | Date range |
|---|---|---|
| Train | 50,857 | 2015-07-01 → 2017-01-30 |
| Validation | 10,898 | 2017-01-30 → 2017-05-16 |
| Test | 10,899 | 2017-05-16 → 2017-08-31 |

## 5. Baseline Models & Results

| Model | Train MAE | Val MAE | Val RMSE | Val MAPE |
|---|---|---|---|---|
| Linear Regression | 24.78 | 20.60 | 27.33 | 29.84% |
| Random Forest (200 trees, max_depth=14) | 11.80 | 18.21 | 25.31 | 23.67% |

**Final test-set evaluation (Random Forest, selected on validation):**
MAE = 25.83, RMSE = 32.95, MAPE = 22.36%.

Random Forest beats Linear Regression on validation (real non-linear
structure in the data). Test MAE (25.83) is worse than validation MAE
(18.21) — the test split is the most recent chronological slice
(May–Aug 2017, summer/peak season), so this is the time-based split
correctly exposing a real generalization gap, not a bug. A random split
would have hidden this.

## 6. Limitations (explicit, not hedged)

- **No `city` or `hotel_star_rating` support** — this model cannot answer
  those two of the API's eight input fields in any way. It only knows "which
  of 2 Portuguese hotels."
- **Currency untested** — data is presumably EUR; no conversion to the
  API's USD-only contract has been validated.
- **`room_type` uses raw PMS letter codes**, not the API's
  `single`/`double`/`twin`/`suite` enum — no crosswalk exists yet.
- **Test-set error is meaningfully worse than validation** — the model
  underperforms on the most recent (peak-season) data relative to how it
  looked during validation.
- **Not integrated into the live API** — `ml/price_predictor.py` is
  untouched; this is a standalone, reproducible offline experiment only.
- **Cancellation filtering and outlier capping were judgment calls**, not
  the only valid choices — documented above so they're auditable, not
  hidden inside the code.

## 7. Reproduce This

```bash
cd agent-ml-service
.venv/bin/pip install pandas numpy scikit-learn joblib   # not yet pinned in requirements.txt
.venv/bin/python training/inspect_dataset.py
.venv/bin/python training/train_baseline.py   # also saves models/hotel_price_baseline.joblib
```

Artifact size was tuned for git-friendliness, not accuracy:
`n_estimators=200, max_depth=14` produced a 66.9MB file; dropping to
`n_estimators=60, max_depth=10, min_samples_leaf=10` gave a **4.5MB** file
with marginally *better* validation MAE (17.93 vs 18.21) — not a tradeoff,
just evidence 200 trees was overkill for this data.

## 8. FastAPI Integration (2026-08-11)

- **Model artifact:** `agent-ml-service/models/hotel_price_baseline.joblib`
  (4.5MB) — **committed to git** (unlike the raw CSV, which is gitignored).
  The live service needs this file to start; it is not regenerated at runtime.
- **Loading:** `ml/price_predictor.py`'s `HotelPricePredictor.__init__` calls
  `joblib.load()` once at instantiation (module-level singleton in
  `ml/routes.py`, same pattern as the old mock). Raises `FileNotFoundError`
  with a clear message if the artifact is missing — fails loud at startup,
  not silently at request time.
- **Feature mapping (`HotelPricePredictor.predict`):**
  - Derived from the request: `lead_time` = check_in − booking_date,
    `nights` = check_out − check_in, `number_of_guests` = request field
    directly, `arrival_month_num` = check_in_date.month.
  - **Held at fixed training-set mode values, NOT from the request:**
    `hotel="City Hotel"`, `reserved_room_type="A"`,
    `market_segment="Online TA"`, `deposit_type="No Deposit"`,
    `customer_type="Transient"`. These 5 of 9 model features have **no
    corresponding field in `HotelPriceRequest` at all** — not a mapping gap,
    an absence. Inventing a mapping (e.g. guessing which letter code means
    "double room") was rejected as fabricating a crosswalk that doesn't
    exist. This was a deliberate decision, confirmed before implementation,
    not an oversight discovered later.
  - **Direct, load-bearing consequence:** `city`, `hotel_star_rating`, and
    `room_type` are validated by the API but currently have **zero effect**
    on the predicted price. Only date range and guest count actually move
    the number.
- **Response:** `model_status="baseline"`, `model_version="baseline-rf-v1"`,
  `is_mock=false`, and `message` spells out the above limitation explicitly
  on every response — not just in this doc.
- **`MockHotelPricePredictor`** still exists in the same file, unused by the
  live route, kept for reference/tests.

## 9. Test Results

23 tests pass (0 failed, 0 skipped) — up from 21: added a direct
artifact-load test and a positive-number sanity check; rewrote 4 tests whose
assumptions were mock-specific (exact mock formula values, city-changes-price
behavior) into either regression snapshots of the real model's output or
explicit tests of the new "city doesn't affect price" behavior. Nothing was
deleted — every changed assertion has an inline comment explaining why.

## 10. Integration Verification

**VERIFIED:** live `uvicorn` run — `/health` 200, real `POST
/api/ml/predict-hotel-price` returns a genuine model prediction, endpoint
present in `/openapi.json`. `MlClient.java`'s camelCase→snake_case conversion
was independently re-derived field-by-field against the real
`HotelPriceRequest` schema — all 8 fields match exactly.

**NOT VERIFIED:** an actual JVM process (Spring Boot) calling this endpoint
over the network end-to-end.

**BLOCKER:** Docker daemon not running locally; `./gradlew` wrapper jar
missing and the system's Gradle (9.6.0) doesn't match the project's pinned
8.12 — same known issue as the previous merge session, not new today.

## 11. What Is NOT Implemented

- `city`, `hotel_star_rating`, `room_type` do not influence predictions (§8)
- No real Spring Boot ↔ FastAPI network round-trip has been executed (§10)
- Java test suite still unverified after any of today's changes (same Gradle blocker as before — today's changes don't touch Java code at all, so this is an unrelated pre-existing gap, not a new one)
- Python tests still not wired into CI
- No currency conversion (USD-only, unchanged from before today)
- Budget allocation, Agent integration — untouched, out of scope today
