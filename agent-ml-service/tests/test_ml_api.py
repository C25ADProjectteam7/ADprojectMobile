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


def test_valid_hotel_request_returns_200():
    response = client.post("/api/ml/predict-hotel-price", json=VALID_REQUEST)
    assert response.status_code == 200


def test_response_is_marked_as_mock():
    response = client.post("/api/ml/predict-hotel-price", json=VALID_REQUEST)
    body = response.json()
    assert body["is_mock"] is True
    assert body["model_status"] == "mock"
    assert body["model_version"] == "mock-v0"
    assert "mock" in body["message"].lower()


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


def test_known_price_formula_produces_exact_value():
    # Tokyo base 160.0 * 4-star 1.4 * double 1.0 = 224.0/night, 3 nights = 672.0
    response = client.post("/api/ml/predict-hotel-price", json=VALID_REQUEST)
    body = response.json()
    assert body["predicted_price_per_night"] == 224.0
    assert body["predicted_total_price"] == 672.0


def test_unknown_city_falls_back_to_default_price():
    req = {**VALID_REQUEST, "city": "Nowhereville"}
    response = client.post("/api/ml/predict-hotel-price", json=req)
    body = response.json()
    # default base 120.0 * 4-star 1.4 * double 1.0 = 168.0/night
    assert body["predicted_price_per_night"] == 168.0


def test_whitespace_only_city_returns_422():
    bad = {**VALID_REQUEST, "city": "   "}
    response = client.post("/api/ml/predict-hotel-price", json=bad)
    assert response.status_code == 422


def test_city_is_trimmed():
    req = {**VALID_REQUEST, "city": "  Tokyo  "}
    response = client.post("/api/ml/predict-hotel-price", json=req)
    assert response.status_code == 200
    assert response.json()["predicted_price_per_night"] == 224.0


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
