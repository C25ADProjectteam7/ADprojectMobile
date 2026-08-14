"""
Tests for the India hotel fair-price V2 pipeline.

Guards the things that would silently break the product:
  * serving-feature parity (no `occ`, currentPrice never a feature)
  * routing (HISTORICAL vs ML) and why each fires
  * decision band boundaries and quantile-crossing post-processing
  * market/currency scope
  * the LiteAPI probe really asks for 1 night / 1 room / 2 adults / INR
"""
from pathlib import Path

import math
from datetime import date, timedelta

import pytest
from fastapi.testclient import TestClient

from main import app
from ml import india_liteapi_probe as probe
from ml.india_hotel_price_predictor import (COMPARISON_BASIS, MAX_LEAD_DAYS,
                                            MIN_LEAD_DAYS,
                                            IndiaHotelPricePredictor, is_india)
from ml.india_serving_features import (CATEGORICAL, FEATURES,
                                       breakfast_category_from_liteapi,
                                       cancellation_category_from_liteapi,
                                       normalize_name, room_category)

MODELS = Path(__file__).resolve().parent.parent / "models"
ARTIFACT = MODELS / "hotel_price_india_hybrid_v1.joblib"
needs_artifact = pytest.mark.skipif(not ARTIFACT.exists(), reason="India artifact not built")
CLEAN = (Path(__file__).resolve().parent.parent / "training" / "data"
         / "promptcloud" / "india_offers_clean.parquet")
needs_clean = pytest.mark.skipif(not CLEAN.exists(), reason="training parquet not present")

# A REAL training hotel with 4 offers - below the >=5 threshold, so it is a
# training hotel that must still be served by ML, not by hotel-own history.
LOW_OBS_TRAINING_HOTEL = "1/1 Park Street Hotel"


def dates(lead_days: int) -> dict:
    """booking/check-in pair with an exact lead time."""
    b = date(2026, 8, 14)
    return {"booking_date": b.isoformat(),
            "check_in_date": (b + timedelta(days=lead_days)).isoformat()}

client = TestClient(app)
# HTTP body uses camelCase; the predictor signature uses snake_case.
BASE = {"currency": "INR", "market": "IN",
        "bookingDate": "2026-08-14", "checkInDate": "2026-08-20"}
PBASE = {"currency": "INR", "market": "IN",
         "booking_date": "2026-08-14", "check_in_date": "2026-08-20"}


@pytest.fixture(scope="module")
def predictor():
    p = IndiaHotelPricePredictor()
    assert p._ensure(), "artifact must load"
    return p


# ------------------------------------------------- A. serving feature parity
def test_occ_is_not_a_feature():
    assert "occ" not in FEATURES
    assert not any("occ" in f for f in FEATURES)


def test_current_price_is_not_a_feature():
    for banned in ("currentPrice", "current_price", "price", "price_rank", "default_rank"):
        assert banned not in FEATURES


def test_feature_schema_is_frozen():
    assert FEATURES == ["hotel_key", "room_category", "breakfast_category",
                        "cancellation_category", "lead_time_days",
                        "ci_month", "ci_dow", "ci_weekend", "cr_dow"]
    assert CATEGORICAL == ["hotel_key", "room_category",
                           "breakfast_category", "cancellation_category"]


@needs_artifact
def test_artifact_schema_matches_code(predictor):
    assert predictor.metadata["featureSchema"] == FEATURES
    assert "occ" in predictor.metadata["excludedFeatures"]
    assert predictor.metadata["comparisonBasis"] == COMPARISON_BASIS
    assert predictor.metadata["trainingContext"]["nights"] == 1
    assert predictor.metadata["trainingContext"]["adults"] == 2
    assert predictor.metadata["trainingCurrency"] == "INR"


@pytest.mark.parametrize("name,expected", [
    ("Deluxe Double Room", "DELUXE"), ("Junior Suite", "SUITE"),
    ("Standard Twin Room", "STANDARD"), ("Superior King Room", "SUPERIOR"),
    ("Studio Apartment", "APARTMENT"), ("Family Room", "FAMILY"),
    ("Bed in 6-Bed Dormitory", "DORM"), ("", "OTHER"),
])
def test_room_mapping(name, expected):
    assert room_category(name) == expected


@pytest.mark.parametrize("bt,bn,expected", [
    ("RO", None, "ROOM_ONLY"), ("BI", None, "BREAKFAST"), ("HB", None, "HALF_BOARD"),
    ("FB", None, "FULL_BOARD"), ("AI", None, "ALL_INCLUSIVE"),
    (None, "Breakfast Included", "BREAKFAST"), (None, "Room Only", "ROOM_ONLY"),
    (None, None, "UNKNOWN"), ("ZZ", "something odd", "UNKNOWN"),
])
def test_breakfast_mapping(bt, bn, expected):
    assert breakfast_category_from_liteapi(bt, bn) == expected


@pytest.mark.parametrize("tag,expected", [
    ("RFN", "REFUNDABLE"), ("NRFN", "NON_REFUNDABLE"),
    (None, "UNKNOWN"), ("weird", "UNKNOWN"),
])
def test_cancellation_mapping(tag, expected):
    assert cancellation_category_from_liteapi(tag) == expected


def test_normalization_is_shared_single_implementation():
    """training/v2mvp_india/serving_features.py must forward to the ml/ module."""
    import importlib.util
    shim = (Path(__file__).resolve().parent.parent / "training" / "v2mvp_india"
            / "serving_features.py")
    spec = importlib.util.spec_from_file_location("shim", shim)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    assert mod.normalize_name is normalize_name
    assert mod.room_category is room_category


# ------------------------------------------------------------- B. routing
@needs_artifact
def test_known_unambiguous_hotel_routes_to_historical(predictor):
    hk = predictor._art["known_hotel_keys"][0]
    r = predictor.predict(hotel_name=hk, current_price=3000, **PBASE)
    assert r["predictionSource"] == "HISTORICAL"
    assert r["hotelMatchedHistorically"] is True


@needs_artifact
def test_unknown_hotel_routes_to_ml(predictor):
    r = predictor.predict(hotel_name="Nonexistent Hotel ZZZ 999", current_price=3000, **PBASE)
    assert r["predictionSource"] == "ML"
    assert r["hotelMatchedHistorically"] is False


@needs_artifact
def test_ambiguous_hotel_routes_to_ml(predictor):
    amb = predictor._art["ambiguous_hotel_keys"]
    if not amb:
        pytest.skip("no ambiguous keys in artifact")
    r = predictor.predict(hotel_name=amb[0], current_price=3000, **PBASE)
    assert r["predictionSource"] == "ML", "ambiguous names must not use hotel-own history"


@needs_artifact
def test_real_training_hotel_with_few_observations_routes_to_ml(predictor):
    """Not a made-up name: this hotel IS in the training data, with 4 offers.

    Below MIN_HOTEL_OBS=5 its own quantiles would be noise, so it must be
    served by ML - this is the path a fake name can never exercise.
    """
    hk = normalize_name(LOW_OBS_TRAINING_HOTEL)
    assert hk not in set(predictor._art["known_hotel_keys"])
    assert hk not in set(predictor._art["ambiguous_hotel_keys"])
    r = predictor.predict(hotel_name=LOW_OBS_TRAINING_HOTEL, current_price=3000, **PBASE)
    assert r["predictionSource"] == "ML"
    assert r["hotelMatchedHistorically"] is False


@needs_artifact
@needs_clean
def test_low_observation_hotels_are_really_in_training_and_all_route_to_ml(predictor):
    """Verify against the source data that such hotels exist and none leak into B2."""
    import pandas as pd
    d = pd.read_parquet(CLEAN, columns=["hotel_name", "hotel_id"])
    d["hk"] = d.hotel_name.map(normalize_name)
    nuq = d.groupby("hk").hotel_id.nunique()
    amb = set(nuq[nuq > 1].index)
    counts = d[~d.hk.isin(amb)].groupby("hk").size()
    low = counts[(counts >= 1) & (counts <= 4)]

    assert len(low) > 0, "dataset must contain hotels below the observation threshold"
    assert normalize_name(LOW_OBS_TRAINING_HOTEL) in low.index, "fixture drifted from data"
    assert not (set(low.index) & set(predictor._art["known_hotel_keys"])), \
        "a hotel with <5 offers leaked into the historical table"

    for hk in sorted(low.index)[:20]:
        assert predictor.predict(hotel_name=hk, current_price=3000,
                                 **PBASE)["predictionSource"] == "ML"


@needs_artifact
def test_ml_path_ignores_current_price_entirely(predictor):
    """M1 counterpart of the B2 isolation test: same unknown-hotel context,
    three very different quotes, one identical prediction."""
    ctx = dict(hotel_name="Completely Unseen Hotel QQQ 4242",
               room_name="Deluxe Double Room", **PBASE)
    lo = predictor.predict(current_price=1.0, **ctx)
    mid = predictor.predict(current_price=5000.0, **ctx)
    hi = predictor.predict(current_price=10_000_000.0, **ctx)
    for f in ("fairPriceP25", "fairPriceP50", "fairPriceP75",
              "decisionLow", "decisionHigh", "predictionSource"):
        assert lo[f] == mid[f] == hi[f], f"{f} moved with the quote"
    assert lo["predictionSource"] == "ML"
    assert {lo["priceLevel"], hi["priceLevel"]} == {"CHEAP", "EXPENSIVE"}


@needs_artifact
def test_ml_path_is_not_a_constant(predictor):
    """Regression guard: CatBoost inference must actually run. If M1 ever
    degraded into a constant/global fallback these would all match."""
    base = dict(hotel_name="Completely Unseen Hotel QQQ 4242", current_price=3000)
    quantiles = lambda r: (r["fairPriceP25"], r["fairPriceP50"], r["fairPriceP75"])

    ref = predictor.predict(room_name="Standard Room", **base, **PBASE)
    by_room = predictor.predict(room_name="Presidential Suite", **base, **PBASE)
    by_lead = predictor.predict(room_name="Standard Room", currency="INR", market="IN",
                                **base, **dates(0))

    assert ref["predictionSource"] == by_room["predictionSource"] == "ML"
    assert quantiles(ref) != quantiles(by_room), "room_category has no effect on M1"
    assert quantiles(ref) != quantiles(by_lead), "lead_time_days has no effect on M1"


# ------------------------------------------------------- C. price decision
@needs_artifact
def test_price_below_band_is_cheap(predictor):
    hk = predictor._art["known_hotel_keys"][0]
    band = predictor.predict(hotel_name=hk, current_price=3000, **PBASE)
    r = predictor.predict(hotel_name=hk, current_price=band["decisionLow"] - 1, **PBASE)
    assert r["priceLevel"] == "CHEAP"


@needs_artifact
def test_price_above_band_is_expensive(predictor):
    hk = predictor._art["known_hotel_keys"][0]
    band = predictor.predict(hotel_name=hk, current_price=3000, **PBASE)
    r = predictor.predict(hotel_name=hk, current_price=band["decisionHigh"] + 1, **PBASE)
    assert r["priceLevel"] == "EXPENSIVE"


@needs_artifact
@pytest.mark.parametrize("at", ["low", "mid", "high"])
def test_exact_boundary_and_inside_is_fair(predictor, at):
    hk = predictor._art["known_hotel_keys"][0]
    b = predictor.predict(hotel_name=hk, current_price=3000, **PBASE)
    price = {"low": b["decisionLow"], "high": b["decisionHigh"],
             "mid": (b["decisionLow"] + b["decisionHigh"]) / 2}[at]
    r = predictor.predict(hotel_name=hk, current_price=price, **PBASE)
    assert r["priceLevel"] == "FAIR", "band boundaries are inclusive"


@needs_artifact
def test_band_always_contains_the_quantiles(predictor):
    for name in ["Unknown Hotel A", "Unknown Hotel B", predictor._art["known_hotel_keys"][3]]:
        r = predictor.predict(hotel_name=name, current_price=3000, **PBASE)
        assert r["decisionLow"] <= r["fairPriceP25"] <= r["fairPriceP50"] \
            <= r["fairPriceP75"] <= r["decisionHigh"]


@needs_artifact
def test_current_price_does_not_change_the_band(predictor):
    """Only the verdict may move with the quote - never the reference."""
    hk = predictor._art["known_hotel_keys"][2]
    a = predictor.predict(hotel_name=hk, current_price=1.0, **PBASE)
    b = predictor.predict(hotel_name=hk, current_price=999_999.0, **PBASE)
    for f in ("fairPriceP25", "fairPriceP50", "fairPriceP75",
              "decisionLow", "decisionHigh", "predictionSource"):
        assert a[f] == b[f]
    assert a["priceLevel"] == "CHEAP" and b["priceLevel"] == "EXPENSIVE"


# ---------------------------------------------------- D. quantile crossing
@needs_artifact
def test_quantile_crossing_is_sorted_out(predictor, monkeypatch):
    """CatBoost crossed on ~0.023% of validation rows; production must sort."""
    monkeypatch.setattr(predictor, "_predict_ml", lambda row: (9000.0, 5000.0, 7000.0))
    r = predictor.predict(hotel_name="Force ML Route ZZZ", current_price=6000, **PBASE)
    assert r["fairPriceP25"] <= r["fairPriceP50"] <= r["fairPriceP75"]
    assert (r["fairPriceP25"], r["fairPriceP50"], r["fairPriceP75"]) == (5000.0, 7000.0, 9000.0)


# ------------------------------------------------------------- E. market
@pytest.mark.parametrize("v,ok", [("IN", True), ("ind", True), ("India", True),
                                  ("TH", False), ("US", False), ("", False), (None, False)])
def test_is_india(v, ok):
    assert is_india(v) is ok


@needs_artifact
def test_non_india_market_is_unavailable(predictor):
    r = predictor.predict(hotel_name="Some Hotel", current_price=3000,
                          currency="INR", market="TH",
                          booking_date="2026-08-14", check_in_date="2026-08-20")
    assert r == {"predictionAvailable": False, "reason": "UNSUPPORTED_MARKET"}


# -------------------------------------------------------------- F. units
@needs_artifact
def test_non_inr_currency_is_rejected(predictor):
    r = predictor.predict(hotel_name="Some Hotel", current_price=3000,
                          currency="USD", market="IN",
                          booking_date="2026-08-14", check_in_date="2026-08-20")
    assert r["predictionAvailable"] is False


@needs_artifact
def test_comparison_basis_is_one_night_two_adults(predictor):
    r = predictor.predict(hotel_name="Any Hotel", current_price=3000, **PBASE)
    assert r["comparisonBasis"] == "PER_NIGHT_1ROOM_2ADULTS"
    assert r["currency"] == "INR" and r["market"] == "IN"


@needs_artifact
def test_invalid_inputs_are_reported_not_predicted(predictor):
    bad = predictor.predict(hotel_name="  ", current_price=3000, **PBASE)
    assert bad == {"predictionAvailable": False, "reason": "INVALID_INPUT"}
    neg = predictor.predict(hotel_name="X", current_price=-5, **PBASE)
    assert neg == {"predictionAvailable": False, "reason": "INVALID_INPUT"}


# --------------------------------------------------------- G. LiteAPI probe
def test_probe_constants_match_training_context():
    assert probe.PROBE_CURRENCY == "INR"
    assert probe.PROBE_ADULTS == 2
    assert probe.PROBE_ROOMS == 1
    assert probe.PROBE_NIGHTS == 1
    assert probe.next_day("2026-09-01") == "2026-09-02"


def test_probe_selects_cheapest_and_retains_rate_context():
    payload = {"data": [{"hotelId": "H1", "roomTypes": [
        {"offerId": "o1", "name": "Deluxe Double Room", "boardType": "BI",
         "boardName": "Breakfast Included", "offerRetailRate": {"amount": 7000, "currency": "INR"},
         "rates": [{"adultCount": 2, "childCount": 0,
                    "cancellationPolicies": {"refundableTag": "RFN"}}]},
        {"offerId": "o2", "name": "Standard Twin Room", "boardType": "RO",
         "boardName": "Room Only", "offerRetailRate": {"amount": 4200, "currency": "INR"},
         "rates": [{"adultCount": 2, "childCount": 0,
                    "cancellationPolicies": {"refundableTag": "NRFN"}}]},
    ]}]}
    o = probe.select_comparable_offer(payload, "H1")
    assert o["offerId"] == "o2" and o["amount"] == 4200
    assert o["roomName"] == "Standard Twin Room"
    assert o["boardType"] == "RO" and o["boardName"] == "Room Only"
    assert o["refundableTag"] == "NRFN"
    assert o["adultCount"] == 2 and o["childCount"] == 0
    assert o["currency"] == "INR"


def test_probe_falls_back_to_retail_rate_total():
    payload = {"data": [{"hotelId": "H1", "roomTypes": [
        {"offerId": "o1", "name": "Suite",
         "rates": [{"retailRate": {"total": [{"amount": 9100, "currency": "INR"}]}}]}]}]}
    o = probe.select_comparable_offer(payload, "H1")
    assert o["amount"] == 9100 and o["currency"] == "INR"


def test_probe_returns_none_when_no_valid_offer():
    assert probe.select_comparable_offer({"data": []}, "H1") is None
    assert probe.select_comparable_offer(
        {"data": [{"hotelId": "H2", "roomTypes": [{"offerRetailRate": {"amount": 100}}]}]},
        "H1") is None


def test_probe_offer_selection_rule_is_declared():
    assert probe.OFFER_SELECTION_RULE == "CHEAPEST_COMPARABLE_ONE_NIGHT"


# ------------------------------------------------------- H. lead-time scope
@needs_artifact
@pytest.mark.parametrize("lead", [MIN_LEAD_DAYS, 1, 7, MAX_LEAD_DAYS])
def test_lead_time_inside_training_support_is_served(predictor, lead):
    r = predictor.predict(hotel_name="Some Hotel", current_price=3000,
                          currency="INR", market="IN", **dates(lead))
    assert r["predictionAvailable"] is True


@needs_artifact
@pytest.mark.parametrize("lead", [16, 30, 365])
def test_lead_time_beyond_training_support_is_declined(predictor, lead):
    """Training support is 0-15 days. We decline rather than extrapolate,
    clamp, or invent a fallback."""
    r = predictor.predict(hotel_name="Some Hotel", current_price=3000,
                          currency="INR", market="IN", **dates(lead))
    assert r == {"predictionAvailable": False, "reason": "UNSUPPORTED_LEAD_TIME"}


@needs_artifact
@pytest.mark.parametrize("lead", [-1, -30])
def test_negative_lead_time_is_invalid_input(predictor, lead):
    """Check-in before booking is malformed input, not an unsupported horizon -
    keeps the pre-existing contract."""
    r = predictor.predict(hotel_name="Some Hotel", current_price=3000,
                          currency="INR", market="IN", **dates(lead))
    assert r == {"predictionAvailable": False, "reason": "INVALID_INPUT"}


@needs_artifact
def test_lead_time_boundary_is_exactly_the_training_range(predictor):
    assert (MIN_LEAD_DAYS, MAX_LEAD_DAYS) == (0, 15)
    lo = predictor.metadata["leadTimeRange"]
    assert [MIN_LEAD_DAYS, MAX_LEAD_DAYS] == list(lo), \
        "guard must match the artifact's recorded training support"


@needs_artifact
def test_endpoint_declines_unsupported_lead_time():
    r = client.post("/api/ml/v2/hotel-price",
                    json={**BASE, "hotelName": "Some Hotel", "currentPrice": 3000,
                          "bookingDate": "2026-08-14", "checkInDate": "2026-09-30"})
    assert r.status_code == 200
    assert r.json() == {"predictionAvailable": False, "reason": "UNSUPPORTED_LEAD_TIME"}


# ---------------------------------------------------- I. numeric validation
@needs_artifact
@pytest.mark.parametrize("bad", [float("nan"), float("inf"), float("-inf"),
                                 0, -1, -0.01])
def test_non_finite_or_non_positive_price_is_rejected(predictor, bad):
    r = predictor.predict(hotel_name="Some Hotel", current_price=bad, **PBASE)
    assert r == {"predictionAvailable": False, "reason": "INVALID_INPUT"}


def test_predictor_never_emits_nan_from_a_nan_quote(predictor):
    r = predictor.predict(hotel_name="Some Hotel", current_price=math.nan, **PBASE)
    assert r["predictionAvailable"] is False


@pytest.mark.parametrize("bad", ["NaN", "Infinity", "-Infinity", 0, -1])
def test_endpoint_rejects_non_finite_or_non_positive_price(bad):
    """Pydantic rejects these at the edge - they never reach the model."""
    r = client.post("/api/ml/v2/hotel-price",
                    json={**BASE, "hotelName": "Some Hotel", "currentPrice": bad})
    assert r.status_code == 422


# ----------------------------------------------------------------- FastAPI
@needs_artifact
def test_v2_endpoint_returns_full_contract(predictor):
    body = {"hotelName": predictor._art["known_hotel_keys"][1], "currentPrice": 4500, **BASE,
            "roomName": "Deluxe Double Room", "boardType": "RO", "refundableTag": "RFN"}
    r = client.post("/api/ml/v2/hotel-price", json=body)
    assert r.status_code == 200
    j = r.json()
    for f in ("predictionAvailable", "predictionSource", "modelVersion",
              "fairPriceP25", "fairPriceP50", "fairPriceP75",
              "decisionLow", "decisionHigh", "currentComparablePrice",
              "priceLevel", "currency", "market", "comparisonBasis",
              "comparisonOfferSelection", "hotelMatchedHistorically"):
        assert f in j, f"missing {f}"
    assert j["predictionSource"] in ("ML", "HISTORICAL")


@needs_artifact
def test_v2_endpoint_unsupported_market():
    r = client.post("/api/ml/v2/hotel-price", json={
        "hotelName": "X", "currentPrice": 100, "currency": "INR", "market": "TH",
        "bookingDate": "2026-08-14", "checkInDate": "2026-08-20"})
    assert r.status_code == 200
    assert r.json() == {"predictionAvailable": False, "reason": "UNSUPPORTED_MARKET"}


def test_v1_endpoint_still_works():
    """V2 must not disturb the existing V1 contract."""
    r = client.post("/api/ml/predict-hotel-price", json={
        "city": "Tokyo", "check_in_date": "2026-08-10", "check_out_date": "2026-08-13",
        "booking_date": "2026-07-31", "hotel_star_rating": 4,
        "room_type": "double", "number_of_guests": 2, "currency": "USD"})
    assert r.status_code == 200
    assert "predicted_price_per_night" in r.json()
