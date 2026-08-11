"""
Tests for ML Hotel Price Prediction API
Run: pytest tests/test_ml_api.py -v
"""

import pytest
from fastapi.testclient import TestClient
from main import app

client = TestClient(app)

VALID_REQUEST = {
    "city": "Tokyo",
    "check_in_date": "2026-08-10",
    "check_out_date": "2026-08-13",
    "booking_date": "2026-07-31",
    "hotel_star_rating": 4,
    "room_type": "double",
    "number_of_guests": 2,
    "currency": "USD",
}


def test_health_check():
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "healthy"


def test_model_artifact_loads_successfully():
    # New: directly instantiate the predictor (not through the route) to
    # isolate "does the joblib artifact load" from "does the route work".
    from ml.price_predictor import HotelPricePredictor
    predictor = HotelPricePredictor()
    assert predictor._pipeline is not None


def test_valid_hotel_request_returns_200():
    response = client.post("/api/ml/predict-hotel-price", json=VALID_REQUEST)
    assert response.status_code == 200


def test_response_is_marked_as_baseline_not_mock():
    # CHANGED 2026-08-11: route now serves HotelPricePredictor (trained
    # baseline), not MockHotelPricePredictor. is_mock must now be False and
    # model_status must reflect the real model stage.
    response = client.post("/api/ml/predict-hotel-price", json=VALID_REQUEST)
    body = response.json()
    assert body["is_mock"] is False
    assert body["model_status"] == "baseline"
    assert body["model_version"] == "baseline-rf-v1"
    assert "baseline" in body["message"].lower()


def test_baseline_prediction_is_a_positive_number():
    # New: model artifact loaded + real inference produced a usable number,
    # not just "some value" — this is not derivable by hand like the mock
    # formula was, so we only assert it's a sane positive price.
    response = client.post("/api/ml/predict-hotel-price", json=VALID_REQUEST)
    body = response.json()
    assert isinstance(body["predicted_price_per_night"], float)
    assert body["predicted_price_per_night"] > 0


def test_total_price_equals_nights_times_per_night():
    response = client.post("/api/ml/predict-hotel-price", json=VALID_REQUEST)
    body = response.json()
    expected_total = round(body["predicted_price_per_night"] * body["number_of_nights"], 2)
    assert body["predicted_total_price"] == expected_total


def test_total_price_equals_nights_times_per_night():
    response = client.post("/api/ml/predict-hotel-price", json=VALID_REQUEST)
    body = response.json()
    expected_total = round(body["predicted_price_per_night"] * body["number_of_nights"], 2)
    assert body["predicted_total_price"] == expected_total


def test_number_of_nights_is_correct():
    response = client.post("/api/ml/predict-hotel-price", json=VALID_REQUEST)
    body = response.json()
    assert body["number_of_nights"] == 3  # Aug 10 → Aug 13


def test_checkout_before_checkin_returns_422():
    bad = {**VALID_REQUEST, "check_in_date": "2026-08-13", "check_out_date": "2026-08-10"}
    response = client.post("/api/ml/predict-hotel-price", json=bad)
    assert response.status_code == 422


def test_checkout_same_as_checkin_returns_422():
    bad = {**VALID_REQUEST, "check_in_date": "2026-08-10", "check_out_date": "2026-08-10"}
    response = client.post("/api/ml/predict-hotel-price", json=bad)
    assert response.status_code == 422


def test_booking_date_after_checkin_returns_422():
    bad = {**VALID_REQUEST, "booking_date": "2026-08-15"}
    response = client.post("/api/ml/predict-hotel-price", json=bad)
    assert response.status_code == 422


def test_invalid_star_rating_too_high_returns_422():
    bad = {**VALID_REQUEST, "hotel_star_rating": 6}
    response = client.post("/api/ml/predict-hotel-price", json=bad)
    assert response.status_code == 422


def test_invalid_star_rating_zero_returns_422():
    bad = {**VALID_REQUEST, "hotel_star_rating": 0}
    response = client.post("/api/ml/predict-hotel-price", json=bad)
    assert response.status_code == 422


def test_invalid_guest_count_zero_returns_422():
    bad = {**VALID_REQUEST, "number_of_guests": 0}
    response = client.post("/api/ml/predict-hotel-price", json=bad)
    assert response.status_code == 422


def test_identical_inputs_produce_identical_output():
    r1 = client.post("/api/ml/predict-hotel-price", json=VALID_REQUEST).json()
    r2 = client.post("/api/ml/predict-hotel-price", json=VALID_REQUEST).json()
    assert r1["predicted_price_per_night"] == r2["predicted_price_per_night"]
    assert r1["predicted_total_price"] == r2["predicted_total_price"]


def test_endpoint_appears_in_openapi_schema():
    response = client.get("/openapi.json")
    assert response.status_code == 200
    paths = response.json()["paths"]
    assert "/api/ml/predict-hotel-price" in paths


def test_baseline_prediction_matches_known_snapshot():
    # CHANGED 2026-08-11: this used to hand-verify the mock's formula
    # (base * star_mult * room_mult). A trained model has no such formula to
    # hand-derive — this is now a regression snapshot of the current
    # artifact's output for VALID_REQUEST, so a future retrain/artifact swap
    # that silently changes predictions gets caught. Update this number
    # deliberately (with a comment why) if the model is intentionally retrained.
    response = client.post("/api/ml/predict-hotel-price", json=VALID_REQUEST)
    body = response.json()
    assert body["predicted_price_per_night"] == 120.65
    assert body["predicted_total_price"] == 361.95


def test_city_does_not_affect_baseline_prediction():
    # CHANGED 2026-08-11: the training dataset has no city field (see
    # docs/ml/hotel-price-baseline-results.md), so city is accepted by the
    # API but intentionally has zero effect on the baseline model's output.
    # This test documents that known limitation as actual behavior, not a bug.
    baseline = client.post("/api/ml/predict-hotel-price", json=VALID_REQUEST).json()
    req = {**VALID_REQUEST, "city": "Nowhereville"}
    response = client.post("/api/ml/predict-hotel-price", json=req)
    body = response.json()
    assert body["predicted_price_per_night"] == baseline["predicted_price_per_night"]


def test_whitespace_only_city_returns_422():
    bad = {**VALID_REQUEST, "city": "   "}
    response = client.post("/api/ml/predict-hotel-price", json=bad)
    assert response.status_code == 422


def test_city_is_trimmed():
    # CHANGED 2026-08-11: was asserting the mock's exact formula value for
    # "Tokyo". City no longer affects the prediction at all (see above), so
    # this now only verifies the trimming/validation behavior in schemas.py
    # still works (still relevant — schemas.py itself did not change today):
    # a whitespace-padded city is accepted (200), not rejected.
    req = {**VALID_REQUEST, "city": "  Tokyo  "}
    response = client.post("/api/ml/predict-hotel-price", json=req)
    assert response.status_code == 200


def test_unsupported_currency_returns_422():
    bad = {**VALID_REQUEST, "currency": "EUR"}
    response = client.post("/api/ml/predict-hotel-price", json=bad)
    assert response.status_code == 422


def test_garbage_currency_returns_422():
    bad = {**VALID_REQUEST, "currency": "123"}
    response = client.post("/api/ml/predict-hotel-price", json=bad)
    assert response.status_code == 422


def test_lowercase_currency_is_normalized_and_accepted():
    req = {**VALID_REQUEST, "currency": "usd"}
    response = client.post("/api/ml/predict-hotel-price", json=req)
    assert response.status_code == 200
    assert response.json()["currency"] == "USD"


def test_invalid_room_type_returns_422():
    bad = {**VALID_REQUEST, "room_type": "penthouse"}
    response = client.post("/api/ml/predict-hotel-price", json=bad)
    assert response.status_code == 422
