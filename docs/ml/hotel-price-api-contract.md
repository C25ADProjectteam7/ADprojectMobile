# Hotel Price Prediction API Contract

## Purpose

Predicts an estimated hotel price for a given city, date range, star rating, and
room type. This document describes the contract as **currently implemented** in
`agent-ml-service/ml/`. It is the interface Spring Boot (or the Agent) integrates
against, and it is intended to stay stable when the mock predictor is replaced by
a real trained model.

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
| `city` | string | Yes | `min_length=1` after stripping whitespace | City to predict pricing for. Whitespace is trimmed before validation/lookup (so `"   "` is rejected as blank, `"  Tokyo  "` is accepted as `"Tokyo"`). Matched case-insensitively against an internal lookup table; unknown cities fall back to a default base price. |
| `check_in_date` | date (`YYYY-MM-DD`) | Yes | must be before `check_out_date` | Stay start date. |
| `check_out_date` | date (`YYYY-MM-DD`) | Yes | must be strictly after `check_in_date` | Stay end date. |
| `booking_date` | date (`YYYY-MM-DD`) | Yes | must be ≤ `check_in_date` | Date the booking is made; used for lead-time context in a future real model. |
| `hotel_star_rating` | int | Yes | `1 ≤ x ≤ 5` | Hotel star rating; drives a price multiplier. |
| `room_type` | string (enum) | Yes | one of `single`, `double`, `twin`, `suite` | Room type; drives a price multiplier. |
| `number_of_guests` | int | Yes | `x ≥ 1` | Number of guests. Accepted and validated, not currently used in the mock price formula. |
| `currency` | string | Yes | must be `USD` (case-insensitive; normalized to uppercase) | Currency code. **Mock stage supports USD only** — city base prices in `price_predictor.py` are USD figures with no FX conversion, so any other currency is rejected with 422 rather than being echoed back mislabeled. |

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
| `model_status` | string enum (`mock` \| `baseline` \| `trained`) | What kind of predictor produced this result. Currently always `"mock"`. |
| `model_version` | string | Identifier for the predictor implementation. Currently always `"mock-v0"`. |
| `is_mock` | boolean | `true` when the result is not from a trained ML model. Currently always `true`. |
| `message` | string | Human-readable disclaimer. Currently states this is a mock result and must not be used for real booking decisions. |

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
  "predicted_price_per_night": 224.0,
  "predicted_total_price": 672.0,
  "number_of_nights": 3,
  "currency": "USD",
  "model_status": "mock",
  "model_version": "mock-v0",
  "is_mock": true,
  "message": "MOCK prediction only — based on fixed lookup tables, not a trained model. Do not use this result for real booking decisions."
}
```

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

- The endpoint is served by `MockHotelPricePredictor`
  (`agent-ml-service/ml/price_predictor.py`): a **deterministic, rule-based**
  lookup (city base price × star-rating multiplier × room-type multiplier).
  It is not a trained machine learning model.
- **There is no real trained model yet.** No dataset has been selected or
  used for training (see `docs/ml/hotel-price-dataset-requirements.md`).
- `model_status`, `model_version`, and `is_mock` exist specifically so callers
  can detect and surface that a result is not a real prediction. Callers
  (Spring Boot UI, Agent) **must not** present this result to end users as a
  genuine ML forecast — the `message` field should be surfaced or the
  `is_mock` flag checked before use.

## Calling the API (Spring Boot / Agent)

The `agent-ml-service` FastAPI app runs independently and exposes this
endpoint over plain HTTP (e.g. `http://agent-ml:8000/api/ml/predict-hotel-price`
— `agent-ml` is the service name defined in `docker-compose.yml` — or
`http://localhost:8000/...` when running outside Docker). Any HTTP client
(Spring `RestTemplate`/`WebClient`, or the Python Agent's own HTTP layer) can
call it with a standard JSON POST.

**No specific caller has been finalized or hard-coded.** This contract is
written so it can be consumed by Spring Boot's business layer, by the Agent
orchestrator, or by both, without any change to the endpoint itself.

## Future Real-Model Replacement

Per `price_predictor.py`'s design:

1. Train a model and persist it (e.g. `joblib.dump(model, "price_predictor.joblib")`).
2. Implement `RealHotelPricePredictor` with the same `predict(request: HotelPriceRequest) -> HotelPriceResponse` interface as `MockHotelPricePredictor`.
3. Swap the import **and** the `_predictor = MockHotelPricePredictor()` instantiation
   in `ml/routes.py` to use `RealHotelPricePredictor` instead.
4. This is intended as a small, contained change — the endpoint path and most
   request/response field names should stay stable — but it is a target, not a
   guarantee. A real model may need `SUPPORTED_CURRENCIES` in `schemas.py`
   widened (currently USD-only because the mock has no FX conversion), or
   additional preprocessing/metadata fields. Only `model_status`,
   `model_version`, and `is_mock` are guaranteed to change to reflect a real model.
