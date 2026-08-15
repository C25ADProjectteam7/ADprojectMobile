# Hotel Price Prediction — Dataset Requirements / Research Note

## Status

**No dataset has been selected, downloaded, or validated yet.** This document
defines what is needed so that dataset search/collection can proceed
independently, and so `MockHotelPricePredictor` can eventually be replaced by
`RealHotelPricePredictor` (see `docs/ml/hotel-price-api-contract.md`).

## Prediction Target

Same as the API contract:
- Primary target: price per night (numeric, continuous)
- Total stay price is derived (`price_per_night × nights`), not a separately
  trained target.

## Unit of Observation

One row = one hotel room booking/listing observation: a specific hotel room,
for a specific stay date range, at a specific booked/quoted price. Not one row
per hotel (a hotel can appear many times with different dates/room types/prices).

## Required Features

These map directly to `HotelPriceRequest` and must be present (or derivable)
in any candidate dataset:
- City / location (ideally normalized — city name or geo-coordinates)
- Check-in date
- Check-out date (or number of nights directly)
- Hotel star rating (1–5)
- Room type (single / double / twin / suite, or a mappable equivalent)
- Currency of the listed price

## Optional Features (nice to have, not required for v1)

- Booking date / lead time (days between booking and check-in) — already
  accepted by the API for this purpose
- Number of guests
- Hotel brand / chain
- Amenities (wifi, breakfast included, pool, etc.)
- Review score / number of reviews
- Seasonality indicators (holiday, weekend, local events)
- Cancellation policy

## Target Column

- `price_per_night` (numeric). If a dataset only provides total stay price,
  it must also provide nights so per-night price can be derived.

## Coverage Considerations

- **City coverage:** should include the cities already in the mock's lookup
  table (Tokyo, Bangkok, Paris, London, New York, Sydney, Singapore, Dubai,
  Bali, Barcelona) at minimum, ideally with enough rows per city for the
  model to learn city-level price levels rather than memorizing single points.
- **Date coverage:** needs multiple dates per city/hotel to capture
  seasonality; a dataset with only one snapshot in time cannot support
  date-based features meaningfully.
- **Hotel-type coverage:** needs a spread across star ratings and room types,
  not just one segment (e.g. not all-luxury or all-budget).

## Currency Handling

- Prefer a dataset in a single currency, or one that includes explicit
  currency per row so conversion can be applied consistently at a fixed
  reference rate/date.
- Do not mix currencies in the target column without normalizing first —
  this would silently corrupt the price scale the model learns.

## Missing Values

- Any row missing the target (price) must be dropped, not imputed.
- Missing categorical features (e.g. room type) should be dropped or
  explicitly bucketed as "unknown" rather than imputed with a guessed category.
- Missing numeric features (e.g. star rating) should be imputed only with a
  documented, defensible strategy (e.g. median within city) — never silently
  zero-filled.

## Categorical Encoding

- `city`, `room_type` are categorical and need encoding (one-hot or target/
  frequency encoding) before use in scikit-learn/XGBoost.
- `hotel_star_rating` is ordinal (1–5) — can be used as a numeric feature
  directly, or one-hot encoded; ordinal treatment is preferred since order is
  meaningful (higher star ≈ higher price).

## Data Leakage Risks

- Do not include any feature that is only known **after** the price is
  determined (e.g. final booking confirmation status, post-stay review score
  for that specific stay) — this would leak target-correlated information not
  available at prediction time.
- Do not let the same hotel/listing appear in both train and test splits with
  near-identical dates/rows — this inflates apparent accuracy by letting the
  model "memorize" rather than generalize.

## Why Not Random Split

Hotel prices are highly time-dependent (seasonality, demand trends, inflation
over the dataset's collection period). A random train/test split would let
future dates leak into training and past dates leak into the test set,
producing an overly optimistic evaluation that does not reflect real-world
performance (predicting future prices from past data only).

## Time-Based Split

- **Train:** earliest chronological portion of the data (e.g. first 70% by
  check-in or snapshot date).
- **Validation:** next chronological slice (e.g. next 15%).
- **Test:** most recent chronological slice (e.g. final 15%).
- No shuffling across the time boundary. This simulates the real deployment
  scenario: predicting prices for dates the model has not seen.

## Baseline Models (candidates, not yet trained)

1. **Linear Regression** — simplest baseline, establishes a lower bound and
   checks for gross non-linearity in residuals.
2. **Random Forest Regressor** — handles non-linear interactions between
   city/star/room-type without heavy feature engineering.
3. **Gradient Boosting / XGBoost** — likely best accuracy ceiling for
   structured/tabular data of this kind; already listed in
   `requirements.txt`.

## Evaluation Metrics

- **MAE (Mean Absolute Error)** — average absolute price error in the
  original currency unit; easiest to explain to non-technical stakeholders.
- **RMSE (Root Mean Squared Error)** — penalizes large errors more, useful to
  catch cases where the model is occasionally very wrong.
- **MAPE (Mean Absolute Percentage Error)** — normalizes error by price
  level, useful since hotel prices span a wide range (budget to luxury) and
  a $20 error means very different things at $50/night vs $500/night.

## Candidate Dataset Source — Selection Criteria

Any dataset considered must be checked against:
- **License:** must permit the intended use (internal project / academic
  coursework); redistribution/commercial restrictions must be checked before
  use.
- **Legality/ToS compliance:** no scraped data obtained in violation of a
  source site's terms of service.
- **Field coverage:** must supply (or allow deriving) all "Required Features"
  above.
- **Size/recency:** enough rows across enough cities/dates to support a
  time-based split with meaningful volume in each split.
- **No PII:** must not contain guest personal data (names, payment info,
  contact details).

## Current Limitation

No dataset has been identified, downloaded, or validated against the criteria
above. This is an explicit **blocker** for training
`RealHotelPricePredictor` — `MockHotelPricePredictor` remains the only
implementation until a dataset is sourced and this document's criteria are
satisfied.
