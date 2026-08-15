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
import numpy as np
from datetime import date, timedelta

import pytest
from fastapi.testclient import TestClient

from main import app
from ml import india_liteapi_probe as probe
from ml.india_hotel_price_predictor import (COMPARISON_BASIS, MAX_LEAD_DAYS,
                                            MIN_LEAD_DAYS,
                                            IndiaHotelPricePredictor, is_india)
from ml.india_serving_features import (CATEGORICAL, FEATURES, V3_CATEGORICAL,
                                       V3_FEATURES, V21_CATEGORICAL,
                                       V21_FEATURES,
                                       breakfast_category_from_liteapi,
                                       cancellation_category_from_liteapi,
                                       chain_category, family, normalize_name,
                                       review_features, room_category,
                                       serving_city, star_bucket, stars_num,
                                       v3_features)

MODELS = Path(__file__).resolve().parent.parent / "models"
ARTIFACT = MODELS / "hotel_price_india_v3_m2.joblib"
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
    counts_art = predictor._art["hotel_obs_counts"]
    assert all(counts_art[k] >= 5 for k in predictor._art["known_hotel_keys"]), \
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
    monkeypatch.setattr(predictor, "_conformalize", lambda a, b, c: (a, c))
    r = predictor.predict(hotel_name="Force ML Route ZZZ", current_price=6000,
                          city="Mumbai", rating=8.0, review_count=100, **PBASE)
    assert r["fairPriceP25"] <= r["fairPriceP50"] <= r["fairPriceP75"]
    f = r["temporalAdjustmentFactor"]
    assert (r["fairPriceP25"], r["fairPriceP50"], r["fairPriceP75"]) == (
        round(5000.0 * f, 2), round(7000.0 * f, 2), round(9000.0 * f, 2))


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
def test_lead_time_guard_covers_the_v3_collected_range(predictor):
    """V3 collected leads 1, 3, 7, 10, 14; the guard must contain them all."""
    assert (MIN_LEAD_DAYS, MAX_LEAD_DAYS) == (0, 15)
    for lead in (1, 3, 7, 10, 14):
        assert MIN_LEAD_DAYS <= lead <= MAX_LEAD_DAYS


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


# =====================================================================
# V2.1 — M2D unseen-hotel fallback, CQR, temporal rebasing
# =====================================================================
@pytest.fixture(scope="module")
def meta(predictor):
    return predictor.metadata


# --------------------------------------------------- V2.1 A. feature schema
def test_v21_feature_schema_is_frozen():
    assert V21_FEATURES == ["family", "city", "review_score", "has_review_score",
                            "log_review_count", "room_category", "breakfast_category",
                            "cancellation_category", "lead_time_days",
                            "ci_month", "ci_dow", "ci_weekend", "cr_dow"]
    assert V21_CATEGORICAL == ["family", "city", "room_category",
                               "breakfast_category", "cancellation_category"]


def test_exact_hotel_identity_is_not_an_unseen_feature():
    """The V1 fallback keyed on hotel_key, which is meaningless for an unseen
    hotel and made every one of them collapse to a single prior."""
    assert "hotel_key" not in V21_FEATURES
    assert "hotel_id" not in V21_FEATURES


def test_v21_has_no_price_leakage():
    for banned in ("currentPrice", "current_price", "price", "price_rank",
                   "default_rank", "offerRetailRate"):
        assert banned not in V21_FEATURES


@needs_artifact
def test_artifact_schema_matches_v3_frozen_spec(meta):
    assert meta["featureSchema"] == V3_FEATURES
    assert meta["categoricalFeatures"] == V3_CATEGORICAL
    assert meta["modelVersion"] == "india-v3-m2"


# --------------------------------------------------------- V3 city mapping
# (name-family tests retired: `family` is not a V3 production feature)
@needs_artifact
def test_city_aliases_are_frozen_and_cover_serving_values(meta, predictor):
    al = predictor._art["city_aliases"]
    for served, expected in (("New Delhi", "Delhi"), ("Bangalore", "Bengaluru"),
                             ("Mumbai", "Mumbai"), ("Chennai", "Chennai"),
                             ("Hyderabad", "Hyderabad")):
        assert serving_city(served, al) == expected


@needs_artifact
def test_city_normalization_is_case_and_space_insensitive(meta, predictor):
    al = predictor._art["city_aliases"]
    assert serving_city("  new   DELHI ", al) == serving_city("New Delhi", al) == "Delhi"


@needs_artifact
def test_unmapped_city_becomes_unknown(meta, predictor):
    assert serving_city("Reykjavik", predictor._art["city_aliases"]) == "UNKNOWN"


# ---------------------------------------------------------- V2.1 E. CQR
@needs_artifact
def test_cqr_is_declared_ml_only_in_metadata(meta):
    assert "ML" in meta["calibration"]["appliesTo"]
    assert meta["calibration"]["qhat"] > 0
    assert 0.48 <= meta["calibration"]["calCoverageCalibrated"] <= 0.52


@needs_artifact
def test_cqr_widens_interval_but_never_moves_p50(predictor, monkeypatch):
    raw = (2000.0, 3000.0, 4000.0)
    monkeypatch.setattr(predictor, "_predict_ml", lambda row: raw)
    r = predictor.predict(hotel_name="Zzz Unseen Cqr Probe", current_price=3000,
                          city="Mumbai", rating=8.0, review_count=100, **PBASE)
    f = r["temporalAdjustmentFactor"]
    assert r["fairPriceP50"] == pytest.approx(raw[1] * f, rel=1e-3), "CQR moved P50"
    assert r["fairPriceP25"] < raw[0] * f, "CQR did not widen the lower endpoint"
    assert r["fairPriceP75"] > raw[2] * f, "CQR did not widen the upper endpoint"


@needs_artifact
def test_cqr_is_not_applied_to_the_historical_path(predictor, meta):
    """HISTORICAL quantiles are hotel-own empirical values and were never part
    of the conformal procedure, so they must pass through unwidened."""
    hk = next(k for k in predictor._art["known_hotel_keys"]
              if k not in set(predictor._art["ambiguous_hotel_keys"]))
    r = predictor.predict(hotel_name=hk, current_price=3000, **PBASE)
    assert r["predictionSource"] == "HISTORICAL"
    raw = predictor._lookup_b2(hk, "OTHER") or predictor._hotel.loc[hk][["p25", "p50", "p75"]]
    p25, p50, p75 = sorted(float(x) for x in (raw[0], raw[1], raw[2]))
    f = r["temporalAdjustmentFactor"]
    assert r["fairPriceP25"] == pytest.approx(p25 * f, rel=1e-3)
    assert r["fairPriceP75"] == pytest.approx(p75 * f, rel=1e-3)


# ------------------------------------------------- V2.1 F. temporal rebasing
@needs_artifact
def test_temporal_factor_is_frozen_and_sourced(meta):
    t = meta["temporalAdjustment"]
    assert t["historicalPathFactor"] == 1.3707
    assert t["source"].startswith("MoSPI")
    assert "6.1.04.2.2.07.0" in t["source"] and "330" in t["source"]
    assert t["currentPriceAdjusted"] is False


@needs_artifact
def test_current_price_is_never_temporally_adjusted(predictor):
    r = predictor.predict(hotel_name="Zzz Unseen Quote Probe", current_price=4321.0,
                          city="Mumbai", stars=4, rating=8.0, review_count=100,
                          chain="NA", hotel_type_id=204, facility_ids=[],
                          room_name="Deluxe Room", board_type="RO",
                          refundable_tag="RFN", **PBASE)
    assert r["currentComparablePrice"] == 4321.0


@needs_artifact
def test_band_is_computed_after_adjustment_and_still_contains_quantiles(predictor):
    for name in ("Zzz Unseen Band A", "Zzz Unseen Band B"):
        r = predictor.predict(hotel_name=name, current_price=5000, city="Mumbai",
                              rating=9.0, review_count=900, **PBASE)
        assert r["decisionLow"] <= r["fairPriceP25"] <= r["fairPriceP50"] \
            <= r["fairPriceP75"] <= r["decisionHigh"]


# ------------------------------------------- V2.1 G. unseen generalization
@needs_artifact
def test_unseen_hotels_no_longer_collapse_to_one_prior(predictor):
    """The original M1 failure this whole line of work exists to fix."""
    base = dict(current_price=5000, city="Mumbai", chain="NA", hotel_type_id=204,
                room_name="Deluxe Room", board_type="RO", refundable_tag="RFN", **PBASE)
    prem = predictor.predict(hotel_name="Zzz Unseen Premium", stars=5, rating=9.2,
                             review_count=3668, facility_ids=list(range(90)), **base)
    budget = predictor.predict(hotel_name="Zzz Unseen Budget", stars=2, rating=7.0,
                               review_count=300, facility_ids=list(range(10)), **base)
    mid = predictor.predict(hotel_name="Zzz Unseen Mid", stars=3, rating=8.0,
                            review_count=500, facility_ids=list(range(40)), **base)
    vals = [x["fairPriceP50"] for x in (prem, budget, mid)]
    assert all(x["predictionSource"] == "ML" for x in (prem, budget, mid))
    assert max(vals) / min(vals) > 1.8, f"unseen profiles collapsed: {vals}"
    assert prem["fairPriceP50"] > mid["fairPriceP50"] > budget["fairPriceP50"]


@needs_artifact
def test_ml_path_still_ignores_the_current_quote(predictor):
    ctx = dict(hotel_name="Zzz Unseen Isolation Probe", city="Mumbai",
               rating=8.5, review_count=500, **PBASE)
    lo = predictor.predict(current_price=1.0, **ctx)
    hi = predictor.predict(current_price=10_000_000.0, **ctx)
    for f in ("fairPriceP25", "fairPriceP50", "fairPriceP75",
              "decisionLow", "decisionHigh", "predictionSource"):
        assert lo[f] == hi[f], f"{f} moved with the quote"
    assert {lo["priceLevel"], hi["priceLevel"]} == {"CHEAP", "EXPENSIVE"}


# ------------------------------------------------------- V2.1 H. provenance
@needs_artifact
def test_currency_is_fixed_by_the_request_contract(meta):
    """V3 is LiteAPI-native: INR is requested explicitly, not inferred."""
    c = meta["requestContract"]
    assert c["currency"] == "INR" and c["guestNationality"] == "IN"
    assert c["rooms"] == 1 and c["adults"] == 2 and c["children"] == 0 and c["nights"] == 1
    assert c["comparisonBasis"] == "PER_NIGHT_1ROOM_2ADULTS"


@needs_artifact
def test_v1_artifact_is_not_overwritten():
    assert (MODELS / "hotel_price_india_hybrid_v1.cbm").exists()
    assert (MODELS / "hotel_price_india_hybrid_v1.joblib").exists()


# ---------------------------------------------------------- V2.1 I. probe
def test_probe_declares_india_guest_nationality():
    assert probe.PROBE_GUEST_NATIONALITY == "IN"
    assert probe.PROBE_CURRENCY == "INR"
    assert probe.PROBE_ADULTS == 2 and probe.PROBE_ROOMS == 1 and probe.PROBE_NIGHTS == 1


def test_probe_profile_exposes_the_v21_signals(monkeypatch):
    """city/rating/reviewCount must reach the model; the Agent search flow
    discards all three."""
    import inspect
    src = inspect.getsource(probe.fetch_hotel_profile)
    for field in ("country", "city", "rating", "reviewCount"):
        assert field in src


# =====================================================================
# V3 — frozen M2 LiteAPI-native model, per-path temporal semantics
# =====================================================================
FROZEN_QHAT = 0.420727990504081       # 0.4207 to 4dp; exact value used on TEST
V3BASE = dict(currency="INR", market="IN",
              booking_date="2026-08-14", check_in_date="2026-08-20")


def _unseen(predictor, **kw):
    """An unseen hotel with full LiteAPI-style metadata -> forces the ML path."""
    base = dict(hotel_name="Zzz Completely Unseen V3 Property", current_price=5000,
                city="Mumbai", stars=4, rating=8.5, review_count=500,
                chain="NA", hotel_type_id=204, facility_ids=list(range(60)),
                room_name="Deluxe Room", board_type="RO", refundable_tag="RFN",
                **V3BASE)
    base.update(kw)
    return predictor.predict(**base)


# ------------------------------------------------- V3 A. artifact provenance
@needs_artifact
def test_v1_and_v21_artifacts_remain_untouched():
    for n in ("hotel_price_india_hybrid_v1.cbm", "hotel_price_india_hybrid_v1.joblib",
              "hotel_price_india_hybrid_v21.cbm", "hotel_price_india_hybrid_v21.joblib"):
        assert (MODELS / n).exists(), f"{n} was removed"


@needs_artifact
def test_v3_ships_the_exact_frozen_binary(meta):
    import hashlib
    got = hashlib.sha256((MODELS / "hotel_price_india_v3_m2.cbm").read_bytes()).hexdigest()
    assert got == meta["frozenModelSha256"]
    assert got.startswith("0b481c38eb45d270"), "shipped model is not the frozen candidate"


@needs_artifact
def test_historical_b2_tables_identical_to_v1(predictor):
    """The historical path must not have moved since v1."""
    import joblib
    v1 = joblib.load(MODELS / "hotel_price_india_hybrid_v1.joblib")
    for key in ("b2_hotel_room", "b2_hotel"):
        a, b = v1[key], predictor._art[key]
        assert a.shape == b.shape and list(a.columns) == list(b.columns)
        for c in ("p25", "p50", "p75", "n"):
            assert np.array_equal(a[c].to_numpy(), b[c].to_numpy()), f"{key}.{c} drifted"
    assert v1["known_hotel_keys"] == predictor._art["known_hotel_keys"]
    assert v1["ambiguous_hotel_keys"] == predictor._art["ambiguous_hotel_keys"]


@needs_artifact
def test_v3_records_disjoint_train_cal_test(meta):
    d = meta["datasetCounts"]
    assert (d["trainHotels"], d["calHotels"], d["testHotels"]) == (852, 150, 173)
    assert "disjoint" in d


# --------------------------------------------- V3 B. frozen feature contract
def test_v3_feature_schema_and_order_are_frozen():
    assert V3_FEATURES == ["market", "star_bucket", "stars_num", "room_category",
                           "board_category", "cancellation_category", "leadTimeDays",
                           "guest_rating", "has_guest_rating", "log_review_count",
                           "chain", "hotelTypeId", "n_facilities"]
    assert V3_CATEGORICAL == ["market", "star_bucket", "room_category",
                              "board_category", "cancellation_category", "chain"]


def test_v3_has_no_hotel_identity_or_price_leakage():
    for banned in ("hotelId", "hotel_id", "hotel_key", "currentPrice", "current_price",
                   "price", "price_rank", "default_rank", "offerRetailRate", "family"):
        assert banned not in V3_FEATURES


def test_v3_does_not_use_raw_facility_vector():
    """Only the COUNT is used; the 482-dim list measured 2.0% incremental."""
    assert "facilityIds" not in V3_FEATURES
    assert "n_facilities" in V3_FEATURES


@needs_artifact
def test_v3_feature_row_matches_frozen_order(meta):
    row = v3_features(market="Mumbai", stars=5, rating=9.2, review_count=100,
                      chain="Taj", hotel_type_id=204, facility_ids=[1, 2],
                      room_name="Deluxe Room", board_type="RO", board_name=None,
                      refundable_tag="RFN", lead_time_days=7)
    assert list(row.keys()) == V3_FEATURES == meta["featureSchema"]


@pytest.mark.parametrize("stars,expected", [
    (None, "UNRATED"), (0, "UNRATED"), (1, "1-2"), (2, "1-2"),
    (3, "3"), (3.5, "3"), (4, "4"), (4.5, "4"), (5, "5")])
def test_v3_star_bucket_semantics(stars, expected):
    assert star_bucket(stars) == expected


@pytest.mark.parametrize("stars,expected", [(None, -1.0), (0, -1.0), (4, 4.0)])
def test_v3_missing_stars_encode_as_minus_one(stars, expected):
    assert stars_num(stars) == expected


@pytest.mark.parametrize("rating", [None, 0, 0.0])
def test_v3_missing_guest_rating_is_nan_with_flag(rating):
    """Missing must mean 'unknown' - NaN plus a flag - never a low score."""
    row = v3_features(market="Mumbai", stars=4, rating=rating, review_count=100,
                      chain=None, hotel_type_id=204, facility_ids=[],
                      room_name="Room", board_type="RO", board_name=None,
                      refundable_tag="RFN", lead_time_days=7)
    assert row["has_guest_rating"] == 0
    assert math.isnan(row["guest_rating"])


def test_v3_present_guest_rating_is_kept_verbatim():
    row = v3_features(market="Mumbai", stars=4, rating=8.5, review_count=100,
                      chain=None, hotel_type_id=204, facility_ids=[],
                      room_name="Room", board_type="RO", board_name=None,
                      refundable_tag="RFN", lead_time_days=7)
    assert row["has_guest_rating"] == 1 and row["guest_rating"] == 8.5


def test_v3_review_count_transform_matches_experiment():
    row = v3_features(market="Mumbai", stars=4, rating=8.5, review_count=3668,
                      chain=None, hotel_type_id=204, facility_ids=[],
                      room_name="Room", board_type="RO", board_name=None,
                      refundable_tag="RFN", lead_time_days=7)
    assert row["log_review_count"] == pytest.approx(math.log1p(3668))
    zero = v3_features(market="Mumbai", stars=4, rating=8.5, review_count=0,
                       chain=None, hotel_type_id=204, facility_ids=[],
                       room_name="Room", board_type="RO", board_name=None,
                       refundable_tag="RFN", lead_time_days=7)
    assert math.isnan(zero["log_review_count"])


def test_v3_facility_feature_is_the_count():
    row = v3_features(market="Mumbai", stars=4, rating=8.5, review_count=10,
                      chain=None, hotel_type_id=204, facility_ids=[1, 2, 3, 4],
                      room_name="Room", board_type="RO", board_name=None,
                      refundable_tag="RFN", lead_time_days=7)
    assert row["n_facilities"] == 4


@pytest.mark.parametrize("v", [None, "", "Not Available", "not available"])
def test_v3_absent_chain_normalizes_to_na(v):
    assert chain_category(v) == "NA"


# ------------------------------------------- V3 C. temporal semantics (CRITICAL)
@needs_artifact
def test_historical_path_applies_1_3707_exactly_once(predictor, meta):
    hk = next(k for k in predictor._art["known_hotel_keys"]
              if k not in set(predictor._art["ambiguous_hotel_keys"]))
    r = predictor.predict(hotel_name=hk, current_price=3000, **V3BASE)
    assert r["predictionSource"] == "HISTORICAL"
    assert r["temporalAdjustmentFactor"] == 1.3707
    raw = predictor._lookup_b2(hk, "OTHER") or predictor._hotel.loc[hk][["p25", "p50", "p75"]]
    p50 = sorted(float(x) for x in (raw[0], raw[1], raw[2]))[1]
    assert r["fairPriceP50"] == pytest.approx(p50 * 1.3707, rel=1e-3), \
        "historical factor applied zero times or twice"


@needs_artifact
def test_ml_path_applies_no_temporal_rebasing(predictor):
    """V3 was trained on 2026 LiteAPI-native rates - a CPI factor would
    double-count inflation."""
    r = _unseen(predictor)
    assert r["predictionSource"] == "ML"
    assert r["temporalAdjustmentFactor"] == 1.0
    assert r["priceBasis"] == "LITEAPI_NATIVE_2026_SNAPSHOT"


@needs_artifact
def test_ml_p50_equals_raw_model_output_unscaled(predictor, monkeypatch):
    monkeypatch.setattr(predictor, "_predict_ml", lambda row: (4000.0, 6000.0, 8000.0))
    monkeypatch.setattr(predictor, "_conformalize", lambda a, b, c: (a, c))
    r = _unseen(predictor)
    assert r["fairPriceP50"] == pytest.approx(6000.0, rel=1e-6), "ML P50 was rescaled"


@needs_artifact
def test_the_two_paths_use_different_factors(predictor, meta):
    t = meta["temporalAdjustment"]
    assert t["historicalPathFactor"] == 1.3707 and t["mlPathFactor"] == 1.0
    assert t["currentPriceAdjusted"] is False


@needs_artifact
def test_current_quote_is_never_adjusted_on_either_path(predictor):
    hk = next(k for k in predictor._art["known_hotel_keys"]
              if k not in set(predictor._art["ambiguous_hotel_keys"]))
    assert predictor.predict(hotel_name=hk, current_price=4321.0,
                             **V3BASE)["currentComparablePrice"] == 4321.0
    assert _unseen(predictor, current_price=4321.0)["currentComparablePrice"] == 4321.0


# ------------------------------------------------------- V3 D. calibration
@needs_artifact
def test_frozen_qhat_is_the_v3_value(meta, predictor):
    assert meta["calibration"]["qhat"] == FROZEN_QHAT
    assert round(meta["calibration"]["qhat"], 4) == 0.4207
    assert predictor._qhat == FROZEN_QHAT


@needs_artifact
def test_cqr_widens_endpoints_only_and_never_moves_p50(predictor, monkeypatch):
    raw = (4000.0, 6000.0, 8000.0)
    monkeypatch.setattr(predictor, "_predict_ml", lambda row: raw)
    r = _unseen(predictor)
    assert r["fairPriceP50"] == pytest.approx(raw[1], rel=1e-6), "CQR moved P50"
    assert r["fairPriceP25"] < raw[0] and r["fairPriceP75"] > raw[2]
    w = raw[2] - raw[0]
    assert r["fairPriceP25"] == pytest.approx(raw[0] - FROZEN_QHAT * w, rel=1e-6)
    assert r["fairPriceP75"] == pytest.approx(raw[2] + FROZEN_QHAT * w, rel=1e-6)


@needs_artifact
def test_cqr_is_not_applied_to_historical(predictor):
    hk = next(k for k in predictor._art["known_hotel_keys"]
              if k not in set(predictor._art["ambiguous_hotel_keys"]))
    r = predictor.predict(hotel_name=hk, current_price=3000, **V3BASE)
    raw = predictor._lookup_b2(hk, "OTHER") or predictor._hotel.loc[hk][["p25", "p50", "p75"]]
    p25, _, p75 = sorted(float(x) for x in (raw[0], raw[1], raw[2]))
    assert r["fairPriceP25"] == pytest.approx(p25 * 1.3707, rel=1e-3)
    assert r["fairPriceP75"] == pytest.approx(p75 * 1.3707, rel=1e-3)


@needs_artifact
def test_calibration_guarantee_is_stated_honestly(meta):
    g = meta["calibration"]["guarantee"]
    assert "GROUP-DISJOINT EMPIRICAL" in g
    assert "NO formal marginal" in g
    assert meta["calibration"]["segmentSpecific"] is False


# ---------------------------------------------------- V3 E. behaviour / band
@needs_artifact
def test_quantiles_are_sorted(predictor):
    r = _unseen(predictor)
    assert r["fairPriceP25"] <= r["fairPriceP50"] <= r["fairPriceP75"]


@needs_artifact
def test_returned_bounds_are_the_bounds_used_for_the_verdict(predictor):
    r = _unseen(predictor)
    at_low = _unseen(predictor, current_price=r["decisionLow"])
    at_high = _unseen(predictor, current_price=r["decisionHigh"])
    assert at_low["priceLevel"] == "FAIR" and at_high["priceLevel"] == "FAIR"


@needs_artifact
def test_band_contains_the_quantiles(predictor):
    r = _unseen(predictor)
    assert r["decisionLow"] <= r["fairPriceP25"] <= r["fairPriceP50"] \
        <= r["fairPriceP75"] <= r["decisionHigh"]


@needs_artifact
@pytest.mark.parametrize("stars", [2, 3, 4, 5])
def test_controlled_star_profiles_stay_class_aware(predictor, stars):
    r = _unseen(predictor, stars=stars)
    assert r["predictionSource"] == "ML" and r["fairPriceP50"] > 0


@needs_artifact
def test_star_class_drives_price_monotonically(predictor):
    p = [_unseen(predictor, stars=s)["fairPriceP50"] for s in (2, 3, 4, 5)]
    assert p == sorted(p), f"predicted P50 not monotonic in stars: {p}"
    assert p[-1] > p[0] * 1.5, "star class barely moves the prediction"


@needs_artifact
def test_ml_ignores_the_current_quote(predictor):
    lo = _unseen(predictor, current_price=1.0)
    hi = _unseen(predictor, current_price=10_000_000.0)
    for f in ("fairPriceP25", "fairPriceP50", "fairPriceP75",
              "decisionLow", "decisionHigh", "predictionSource"):
        assert lo[f] == hi[f], f"{f} moved with the quote"


@needs_artifact
def test_liteapi_metadata_reaches_the_predictor(predictor):
    """stars/chain/facilities must actually change the prediction."""
    a = _unseen(predictor, stars=2, chain="NA", facility_ids=[])
    b = _unseen(predictor, stars=5, chain="Taj", facility_ids=list(range(80)))
    assert a["fairPriceP50"] != b["fairPriceP50"]


@needs_artifact
def test_v3_model_version_and_provider(meta):
    assert meta["modelVersion"] == "india-v3-m2"
    assert meta["trainingProvider"] == "LiteAPI"
    assert meta["collectionVersion"] == "v3-liteapi-native-1"


@needs_artifact
def test_metrics_are_not_described_as_accuracy(meta):
    assert "NOT an accuracy" in meta["testMetrics"]["note"]
    assert meta["testMetrics"]["M2_MdAPE"] == 0.289


def test_probe_exposes_all_v3_attributes():
    import inspect
    src = inspect.getsource(probe.fetch_hotel_profile)
    for f in ("country", "city", "rating", "reviewCount",
              "stars", "chain", "hotelTypeId", "facilityIds"):
        assert f in src
