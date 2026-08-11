# Hotel Price V2 — Unified Dataset Build

Date: 2026-08-11. Implements **Option B** from the V2 city-validation report:
merge the 8 approved Inside Airbnb cities into one training table, apply a
uniform short-stay filter, and normalize every price to USD at fixed
reference rates. V1 (`baseline-rf-v1`, commit `35390b5`) is untouched.

Every number below was produced by actually running the pipeline
(`training/v2/build_dataset.py`), not estimated from the validation report.

## Pipeline

| Stage | File | Committed? |
|---|---|---|
| Download + per-city validation | `agent-ml-service/training/v2/validate_cities.py` | yes |
| FX reference rates + self-check | `agent-ml-service/training/v2/fx_rates.py` | yes |
| ECB daily rate series (audit trail) | `agent-ml-service/training/v2/reference/ecb_usd_rates_2026-06.json` | yes |
| Unified build | `agent-ml-service/training/v2/build_dataset.py` | yes |
| Build statistics | `agent-ml-service/training/v2/reference/build_report.json` | yes |
| Raw city CSVs + built dataset | `agent-ml-service/training/data/v2/` | **no** (gitignored) |

Reproduce with:

```bash
cd agent-ml-service
pip install -r requirements-training.txt          # adds pyarrow on top of the runtime deps
python training/v2/validate_cities.py             # downloads ~180 MB, skips existing
python training/v2/build_dataset.py
```

`requirements-training.txt` exists so the parquet engine (`pyarrow`, ~131 MB)
stays out of the production image: the FastAPI runtime loads a joblib pipeline
and never touches parquet, so `requirements.txt` and the `Dockerfile` are
unchanged by V2.

## Currency resolution — upgraded from assumption to evidence

The validation report assigned currency **by city, from price magnitude**,
because the `price` column's symbol is a literal `$` in all 8 cities. While
building, a stronger source was found: `listings.csv` carries a
`price_quote_raw` JSON blob containing an explicit ISO currency code.

Counts below are as recorded in `build_report.json`, which measures currency
evidence **at the short-stay stage (179,363 rows)** — before the date and
price-bound filters. The final-dataset split is given alongside for clarity.

| Evidence source | Rows at evidence stage | Rows in final dataset | Cities |
|---|---|---|---|
| Explicit ISO code in `price_quote_raw` | 158,518 | 156,594 | 7 (Tokyo, Paris, Barcelona, London, NYC, Singapore, Sydney) |
| Currency symbol in quote line items | 20,845 | 20,693 | 1 (Bangkok — its `"currency"` is `null`, but line-item price strings are prefixed `฿`) |
| City assumption (fallback, unused) | 0 | 0 | — |

**All 7 ISO-coded cities matched the magnitude-based assignment exactly**, and
Bangkok's THB is confirmed by the baht symbol rather than inferred. The
build treats any ISO code that contradicts the expected city currency as a
hard failure (`resolve_currency` raises), so this cannot silently regress.

## USD normalization

Rates are **June 2026 ECB monthly averages** (22 business days), pinned in
`fx_rates.py`, matching the 2026-06-14…06-30 snapshot window. Live FX is
deliberately not used — re-running the build next month must not move the
target. Intra-month spread was 1.6%–4.3%, so a monthly mean is representative
rather than an arbitrary single day.

| Currency | Units per 1 USD |
|---|---|
| USD | 1.0 |
| JPY | 160.72500 |
| THB | 32.88486 |
| EUR | 0.86828 |
| GBP | 0.74994 |
| SGD | 1.28790 |
| AUD | 1.42320 |

`fx_rates.verify_rates()` recomputes these from the committed ECB series and
raises if the hardcoded table drifts from its stated source. `build_dataset.py`
calls it before doing any work.

## Filters applied (uniform across all 8 cities)

1. `price_quote_price_per_night` present and > 0 (as in validation).
2. `minimum_nights ≤ 7` — the hotel-comparable short-stay segment.
3. Valid check-in date and `1 ≤ nights ≤ 30`.
4. `10 ≤ price_usd ≤ 2000` — drops data-entry noise and ultra-luxury rows.

Filters 3–4 are new at build time and cost **2,076 rows (1.2%)** against the
179,363-row short-stay pool from validation. They were chosen as fixed
documented rules, not tuned to improve any metric.

## Result

| City | Raw | Usable | Short-stay | Final | Median USD | Hotel-like share |
|---|---|---|---|---|---|---|
| Tokyo | 34,419 | 32,359 | 28,647 | 28,523 | $125 | 6.4% |
| London | 92,638 | 62,240 | 58,872 | 58,059 | $243 | 2.2% |
| Paris | 77,679 | 48,402 | 40,969 | 40,436 | $263 | 3.5% |
| Bangkok | 31,069 | 28,987 | 20,845 | 20,693 | $55 | 14.0% |
| Sydney | 20,573 | 17,784 | 15,566 | 15,380 | $217 | 2.8% |
| Barcelona | 15,293 | 13,355 | 8,101 | 7,910 | $294 | 5.9% |
| New York City | 30,259 | 21,514 | 4,985 | 4,912 | $304 | 29.6% |
| Singapore | 3,097 | 2,592 | 1,378 | 1,374 | $179 | 38.4% |
| **Total** | **305,027** | **227,233** | **179,363** | **177,287** | **$200** | **5.8%** |

- 177,287 rows, 8 cities, 177,287 distinct listings (one quote per listing —
  no property repeats across rows, so no same-property train/test leakage).
- Check-in dates span **2026-06-14 … 2027-07-01**. See the section below for
  what this does and does not support.
- Hotel-like rows: 10,313 (5.8%).

## What the date axis actually supports

This must not be overstated, so stated precisely:

**All 177,287 quotes were collected in a single scrape window (2026-06-14 …
2026-06-30).** What varies across rows is the *requested stay date*, which
extends up to 2027-07-01. Every row is therefore a **forward-looking asking
price observed at one moment in time**, not a transacted price and not a
historical observation.

What this **does** support:
- A chronological split on `checkin_date` — train on nearer stay dates,
  evaluate on later ones.
- Freedom from same-listing train/test leakage, because each listing appears
  exactly once (verified: 177,287 distinct `listing_id` in 177,287 rows).
- Learning **seasonality and lead-time effects** as priced by hosts within
  this single snapshot.

What this does **NOT** support, and must not be claimed:
- **Historical market observations across multiple snapshot dates.** There is
  no second snapshot, so genuine market drift — repricing, demand shifts,
  inflation, FX movement, supply changes — is entirely unobserved.
- **A true forecasting evaluation.** A stay-date split still draws train and
  test from the same scrape and the same pricing regime, so a held-out "later
  stay date" is not the same as a held-out *future*. Metrics from this split
  measure interpolation across a forward booking curve, not the model's
  ability to predict tomorrow's market.
- **Any claim of temporal generalization.** Demonstrating that requires
  additional quarterly snapshots (planned, not done).

Model cards and API messaging should describe V2 as trained on a
single-snapshot forward booking curve, in the same spirit as V1's disclosure
that city/star/room had no effect.

### Sanity checks that passed

**Cross-city price ordering is economically coherent** after conversion —
Bangkok $55 < Tokyo $125 < Singapore $179 < Sydney $217 < London $243 <
Paris $263 < Barcelona $294 < NYC $304. Under the V1-style failure (currency
mislabeled), Tokyo and Bangkok would have dominated the target scale.

**Room-type ordering is coherent:** Entire home/apt $243, Hotel room $230,
Private room $107, Shared room $31.

**NYC/Barcelona medians rose well above their local-currency medians in the
validation report** ($304 vs $174.7; $294 vs €177.7). This is the short-stay
filter, not a conversion error — verified directly on the NYC source file:
long-stay listings (`minimum_nights > 7`) have a median of **$142.9** while
short-stay listings have **$308.5**, because monthly rentals carry large
per-night discounts. Excluding them raises the median as expected.

## Columns in the built dataset

Target: `price_usd`. `price_local` and `source_currency` are retained for
auditing, **not** for training.

Identity/geo: `listing_id`, `city`, `latitude`, `longitude`, `neighbourhood`
Dates: `checkin_date`, `checkout_date`, `scraped_date`, `nights`,
`lead_time_days`, `arrival_month`, `arrival_dow`, `is_weekend_checkin`
Property: `property_type`, `room_type`, `is_hotel_like`, `accommodates`,
`bedrooms`, `beds`, `bathrooms`, `minimum_nights`
Reputation: `review_scores_rating`, `number_of_reviews`

### Deliberately excluded

- `price`, `price_quote_total_price`, `estimated_revenue_l365d` — all
  functions of the target (leakage).
- `instant_bookable` — verified **100% null** in all 8 snapshots.
- V1's `market_segment` / `deposit_type` / `customer_type` — the
  booking-outcome fields flagged QUESTIONABLE in the V1 audit have no
  equivalent here and are not reintroduced.

### Null rates in the final table

`bathrooms` 0.1%, `beds` 5.7%, `bedrooms` 16.5%, `review_scores_rating`
16.6%. `bathrooms` is near-complete only because the build parses
`bathrooms_text` ("1.5 shared baths" → 1.5, "Half-bath" → 0.5) to fill the
sparse numeric column. The remaining nulls need an imputation decision at
training time — `review_scores_rating` is missing for listings with no
reviews yet, which is informative missingness, not random.

## Known limitations (carried forward)

- **Single scrape snapshot.** Stay dates spread across a year, but all quotes
  were captured in one window; true temporal depth needs additional quarterly
  snapshots. See "What the date axis actually supports" above — no temporal
  generalization claim may be made from this dataset.
- **Asking prices, not transactions.** The target is what a host quoted for an
  unbooked future stay. Realized/paid rates and actual occupancy are unobserved,
  so the model learns list-price behaviour rather than clearing prices.
- **Short-term-rental platform, not a hotel inventory.** Hotel-like rows are
  5.8% of the pool. The model will mostly learn the broader short-stay
  accommodation market.
- **No star rating** anywhere in the source — V2 declares star rating
  unsupported rather than fabricating it. The API contract must keep saying so.
- **City imbalance.** London (58k) and Paris (40k) are ~56% of the pool;
  Singapore (1.4k) and NYC (4.9k) are thin. Per-city error must be reported
  separately at evaluation time — a good global MAE could hide bad Singapore
  performance.

## Next step

Feature/target design and the V2 training run, including the chronological
split on `checkin_date`, imputation decisions, and per-city evaluation.
Nothing has been trained yet.
