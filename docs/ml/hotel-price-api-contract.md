# Hotel Price Prediction API Contract

## Purpose

Predicts an estimated hotel price for a given city, date range, star rating, and
room type. This document describes the contract as **currently implemented** in
`agent-ml-service/ml/`. It is the interface Spring Boot (`MlController`/`MlClient`)
integrates against.

**Status as of 2026-08-11:** the endpoint now serves a trained **baseline**
model (`HotelPricePredictor`, RandomForest — see
`docs/ml/hotel-price-baseline-results.md`), not the mock. The request/response
*field names* did not change. What changed is *which fields actually affect
the prediction* — see "Current Implementation Status" below before assuming
this behaves like a full city/star-rating-aware model.

## Endpoint

```
POST /api/ml/predict-hotel-price
```

- **HTTP Method:** POST
- **Content-Type (request):** `application/json`
- **Content-Type (response):** `application/json`

## Prediction Target

The API predicts **both**:
- `predicted_price_per_night` — the estimated price for a single night
- `predicted_total_price` — the estimated total cost for the full stay
  (`predicted_price_per_night × number_of_nights`)

`number_of_nights` is computed by the API as `check_out_date - check_in_date`
(in days) and returned in the response so the caller does not need to
recompute it.

## Request Schema — `HotelPriceRequest`

| Field | Type | Required | Validation | Purpose |
|---|---|---|---|---|
| `city` | string | Yes | `min_length=1` after stripping whitespace | City to predict pricing for. Whitespace is trimmed (so `"   "` is rejected as blank, `"  Tokyo  "` is accepted as `"Tokyo"`). **Not currently used by the baseline model** — the training dataset has no city field at all (only 2 fixed Portuguese hotels), so this value is validated but has zero effect on the prediction. |
| `check_in_date` | date (`YYYY-MM-DD`) | Yes | must be before `check_out_date` | Stay start date. |
| `check_out_date` | date (`YYYY-MM-DD`) | Yes | must be strictly after `check_in_date` | Stay end date. |
| `booking_date` | date (`YYYY-MM-DD`) | Yes | must be ≤ `check_in_date` | Date the booking is made; used for lead-time context in a future real model. |
| `hotel_star_rating` | int | Yes | `1 ≤ x ≤ 5` | Hotel star rating. Accepted and validated, but **not currently used by the baseline model** — the training dataset has no star-rating data at all. |
| `room_type` | string (enum) | Yes | one of `single`, `double`, `twin`, `suite` | Room type. Accepted and validated, but **not currently used by the baseline model** — the training dataset's room codes have no verified mapping to this enum. |
| `number_of_guests` | int | Yes | `x ≥ 1` | Number of guests. **Used by the baseline model** — one of the 4 fields that actually affects the prediction. |
| `currency` | string | Yes | must be `USD` (case-insensitive; normalized to uppercase) | Currency code. Only `USD` accepted — training data has no confirmed currency (presumed EUR, unconverted), so anything else is rejected with 422 rather than mislabeled. |

Cross-field validation (enforced via a Pydantic `model_validator`):
- `check_out_date` must be strictly after `check_in_date`.
- `booking_date` must not be later than `check_in_date`.

Any violation returns **HTTP 422** with FastAPI's standard validation error body.

## Response Schema — `HotelPriceResponse`

| Field | Type | Description |
|---|---|---|
| `predicted_price_per_night` | float | Estimated price for one night, in `currency`. |
| `predicted_total_price` | float | Estimated total price for the full stay, in `currency`. |
| `number_of_nights` | int | Number of nights between check-in and check-out. |
| `currency` | string | Echo of the request's `currency`. No conversion applied. |
| `model_status` | string enum (`mock` \| `baseline` \| `trained`) | What kind of predictor produced this result. Currently always `"baseline"`. |
| `model_version` | string | Identifier for the predictor implementation. Currently always `"baseline-rf-v1"`. |
| `is_mock` | boolean | `true` when the result is not from a trained ML model. Currently always `false`. |
| `message` | string | Human-readable disclaimer. Currently states which input fields the baseline model does and does not actually use — always read this before trusting a result. |

## Example Request

```json
POST /api/ml/predict-hotel-price
Content-Type: application/json

{
  "city": "Tokyo",
  "check_in_date": "2026-08-10",
  "check_out_date": "2026-08-13",
  "booking_date": "2026-07-31",
  "hotel_star_rating": 4,
  "room_type": "double",
  "number_of_guests": 2,
  "currency": "USD"
}
```

## Example Successful Response (200)

```json
{
  "predicted_price_per_night": 120.65,
  "predicted_total_price": 361.95,
  "number_of_nights": 3,
  "currency": "USD",
  "model_status": "baseline",
  "model_version": "baseline-rf-v1",
  "is_mock": false,
  "message": "BASELINE model (RandomForest trained on the Hotel Booking Demand dataset). Only lead time, stay length, guest count, and arrival month currently affect this prediction. city, room_type, and other inputs are accepted by the API but NOT used by this model — the training dataset has no city or star-rating data, and room_type has no verified mapping to the dataset's room codes. Do not treat this as reflecting real city or room-type price differences."
}
```

This is a real, verified response (captured from a live run on 2026-08-11, not
hand-computed) — note the numbers are notably lower than the old mock example
(224.0/672.0) because the underlying data/model changed, not because of a bug.

## Example Validation Error (422)

Request with `check_out_date` before `check_in_date`:

```json
{
  "city": "Tokyo",
  "check_in_date": "2026-08-13",
  "check_out_date": "2026-08-10",
  "booking_date": "2026-07-31",
  "hotel_star_rating": 4,
  "room_type": "double",
  "number_of_guests": 2,
  "currency": "USD"
}
```

Response:

```json
{
  "detail": [
    {
      "type": "value_error",
      "loc": ["body"],
      "msg": "Value error, check_out_date must be after check_in_date",
      "input": { "...": "..." }
    }
  ]
}
```

This is FastAPI/Pydantic's default validation error shape — no custom error
schema has been implemented on top of it.

## Current Implementation Status

- The endpoint is served by `HotelPricePredictor`
  (`agent-ml-service/ml/price_predictor.py`): loads a trained
  `RandomForestRegressor` pipeline (`models/hotel_price_baseline.joblib`)
  and predicts ADR (Average Daily Rate) as the per-night price. **This is a
  real trained model**, not a lookup table — but a first baseline, trained
  on a single dataset with real gaps (see below).
- **Only 4 of the 8 request fields actually influence the prediction:**
  `check_in_date`/`check_out_date` (→ lead time, stay length, arrival month)
  and `number_of_guests`. `city`, `hotel_star_rating`, and `room_type` are
  validated but have **zero effect** on the output — the training dataset
  (Hotel Booking Demand, Antonio/Almeida/Nunes 2019) has no city, star
  rating, or comparable room-type data. See
  `docs/ml/hotel-price-baseline-results.md` for the full feature-availability
  analysis.
- `MockHotelPricePredictor` still exists in the same file for reference/testing
  but is no longer wired into the live route.
- `model_status`, `model_version`, and `is_mock` exist specifically so callers
  can detect what produced a result. `is_mock=false` now means "this came from
  a trained model" — it does **not** mean "this uses every input field
  meaningfully." Callers should surface the `message` field, which spells out
  the current limitation explicitly, rather than assume `is_mock=false` means
  full-fidelity.

## Calling the API (Spring Boot / Agent)

The `agent-ml-service` FastAPI app runs independently and exposes this
endpoint over plain HTTP (e.g. `http://agent-ml:8000/api/ml/predict-hotel-price`
— `agent-ml` is the service name defined in `docker-compose.yml` — or
`http://localhost:8000/...` when running outside Docker). Any HTTP client
(Spring `RestTemplate`/`WebClient`, or the Python Agent's own HTTP layer) can
call it with a standard JSON POST.

**Update (2026-08-11):** Spring Boot now has a real caller — `MlClient.java`
(`mobile-business/.../agent/`) + `MlController.java`
(`mobile-api/.../controller/`), which proxy `POST /api/ml/predict-hotel-price`
for the Android app, converting camelCase to snake_case at the boundary. The
Agent (`agent-ml-service/agent/`) is still unimplemented and does not call
this yet.

## Future Improvement (Baseline → Fuller Model)

The mock→baseline swap already happened and validated the design in
`price_predictor.py`: the route (`ml/routes.py`) only needed its predictor
import and instantiation changed, nothing in `ml/schemas.py` or the endpoint
path. The next real gap is **data, not code**: to make `city` and
`hotel_star_rating` actually affect predictions, a second dataset (or a
richer one) with that information would need to be sourced and reconciled
with the current features — see the "Recommendation" section of
`docs/ml/hotel-price-dataset-shortlist.md` for why that was deliberately
deferred past this baseline.
