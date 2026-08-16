"""
Current-trip candidate-context adjustment.

The band a hotel is judged against is scaled by the median live/raw ratio of
the OTHER hotels the Agent offered for the same trip. The two properties that
matter most, and that these tests exist to protect:

  * NO CIRCULARITY - the hotel being judged never contributes to the factor
    that moves its own band.
  * The ML path only - HISTORICAL quantiles are already CPI-rebased and are
    left exactly as they were.

Everything degrades to the pre-adjustment response: any failure, any shortage
of context, and the caller gets the old numbers back rather than an error.
"""
from datetime import date
from pathlib import Path
from unittest.mock import AsyncMock, patch

import pytest
from fastapi.testclient import TestClient

from main import app
from ml import routes
from ml.india_context_adjustment import (
    CLAMP_HIGH, CLAMP_LOW, CONTEXT_BASIS, MIN_CONTEXT_HOTELS,
    REASON_APPLIED, REASON_HISTORICAL_EXCLUDED, REASON_INSUFFICIENT_CONTEXT,
    REASON_NO_CONTEXT_HOTELS, REASON_PROBE_FAILED,
    compute_context_factor, context_ratio, no_adjustment,
)
from ml.india_hotel_price_predictor import IndiaHotelPricePredictor

MODELS = Path(__file__).resolve().parent.parent / "models"
ARTIFACT = MODELS / "hotel_price_india_v3_m2.joblib"
needs_artifact = pytest.mark.skipif(not ARTIFACT.exists(), reason="India artifact not built")

client = TestClient(app)
CHECK_IN, BOOKING = "2026-08-20", "2026-08-14"
TARGET = "target-hotel"

# A real training hotel: routes to HISTORICAL, used to pin the exclusion rules.
HISTORICAL_HOTEL = "The Oberoi Mumbai"


@pytest.fixture(scope="module")
def predictor():
    p = IndiaHotelPricePredictor()
    assert p._ensure(), "artifact must load"
    return p


def _probe(hotel_id, price, name=None, city="Mumbai", stars=4):
    """A get_fair_price_probes_batch entry."""
    return {"available": True, "hotelId": hotel_id,
            "hotelName": name or f"Zzz Unseen Context {hotel_id}",
            "country": "India", "city": city, "rating": 8.0, "reviewCount": 400,
            "stars": stars, "chain": "NA", "hotelTypeId": 204, "facilityIds": [1, 2, 3],
            "checkIn": CHECK_IN, "checkOut": "2026-08-21", "nights": 1, "rooms": 1,
            "adults": 2, "currency": "INR", "comparableOneNightPrice": price,
            "comparisonOfferSelection": "CHEAPEST_COMPARABLE_ONE_NIGHT",
            "roomName": "Deluxe Room", "boardType": "RO", "boardName": "Room Only",
            "refundableTag": "RFN", "offerId": f"offer-{hotel_id}"}


def _call(candidates=None, target_price=9000.0, target_name="Zzz Unseen Target Hotel",
          batch=None, batch_raises=False):
    """POST the endpoint with the LiteAPI layer mocked out."""
    target = _probe(TARGET, target_price, name=target_name)
    body = {"hotelId": TARGET, "hotelName": target_name,
            "bookingDate": BOOKING, "checkInDate": CHECK_IN}
    if candidates is not None:
        body["candidateHotels"] = candidates

    batch_mock = AsyncMock(side_effect=RuntimeError("liteapi down")) if batch_raises \
        else AsyncMock(return_value=batch or {})
    with patch.object(routes, "get_fair_price_probe", AsyncMock(return_value=target)), \
         patch.object(routes, "get_fair_price_probes_batch", batch_mock):
        response = client.post("/api/ml/v2/hotel-price/by-hotel-id", json=body)
    assert response.status_code == 200, response.text
    return response.json(), batch_mock


# =====================================================================
# 1. Pure factor logic
# =====================================================================
def test_median_not_mean_so_one_outlier_cannot_dominate():
    """Requirement 7. mean([0.9,0.95,1.0,2.63]) = 1.37 and would be clamped;
    the median is 0.975 and barely moves."""
    got = compute_context_factor([0.9, 0.95, 1.0, 2.63])
    assert got["factor"] == 0.975
    assert got["clamped"] is False


def test_even_count_uses_the_true_median():
    assert compute_context_factor([0.8, 0.9, 1.0, 1.1])["factor"] == 0.95


def test_clamp_lower_bound():
    got = compute_context_factor([0.40, 0.45, 0.50])
    assert got["factor"] == CLAMP_LOW == 0.70
    assert got["rawFactor"] == 0.45 and got["clamped"] is True


def test_clamp_upper_bound():
    got = compute_context_factor([1.80, 1.90, 2.00])
    assert got["factor"] == CLAMP_HIGH == 1.30
    assert got["rawFactor"] == 1.90 and got["clamped"] is True


def test_a_factor_inside_the_clamp_is_not_marked_clamped():
    got = compute_context_factor([0.92, 0.83, 0.82, 1.06])
    assert CLAMP_LOW < got["factor"] < CLAMP_HIGH
    assert got["clamped"] is False and got["reason"] == REASON_APPLIED


def test_fewer_than_two_ratios_means_no_adjustment():
    """Requirement 3. One hotel is an anecdote, not a distribution."""
    for ratios in ([], [0.9]):
        got = compute_context_factor(ratios)
        assert got["applied"] is False and got["factor"] == 1.0
        assert got["reason"] == REASON_INSUFFICIENT_CONTEXT
    assert MIN_CONTEXT_HOTELS == 2


def test_non_finite_and_non_positive_ratios_are_dropped():
    got = compute_context_factor([float("nan"), float("inf"), 0.0, -1.0, 0.9, 1.1])
    assert got["validContextHotelCount"] == 2 and got["factor"] == 1.0
    assert got["applied"] is True


def test_context_ratio_rejects_unusable_inputs():
    assert context_ratio(9000, 9000) == 1.0
    for live, raw in ((None, 100), (100, None), (0, 100), (100, 0), (-5, 100),
                      (float("nan"), 100), (100, float("inf")), ("x", 100)):
        assert context_ratio(live, raw) is None


def test_no_adjustment_is_the_identity():
    got = no_adjustment(REASON_PROBE_FAILED)
    assert got["applied"] is False and got["factor"] == 1.0
    assert got["basis"] == CONTEXT_BASIS


@needs_artifact
def test_naming_is_candidate_context_never_market_price():
    """Requirement 12. Checks the identifiers and the values that actually
    reach a caller - not prose, since the module docstring legitimately spells
    out what this is NOT."""
    assert CONTEXT_BASIS == "CURRENT_TRIP_CANDIDATE_CONTEXT"
    body, _ = _call(candidates=[{"hotelId": "c1"}, {"hotelId": "c2"}],
                    batch={"c1": _probe("c1", 8500.0), "c2": _probe("c2", 8700.0)})
    assert body["contextAdjustmentBasis"] == CONTEXT_BASIS
    for key, value in body.items():
        for text in (key, str(value)):
            assert "marketprice" not in text.lower().replace("_", "").replace(" ", "")


# =====================================================================
# 2. Predictor-level behaviour
# =====================================================================
@needs_artifact
def test_factor_one_reproduces_the_pre_adjustment_response(predictor):
    """Requirement 11/13. The backward-compatibility guarantee."""
    base = dict(hotel_name="Zzz Unseen Property", current_price=9000.0, currency="INR",
                market="IN", booking_date=BOOKING, check_in_date=CHECK_IN,
                room_name="Deluxe Room", board_type="RO", refundable_tag="RFN",
                city="Mumbai", rating=8.2, review_count=900, stars=5,
                chain="Taj", hotel_type_id=204, facility_ids=[1, 2, 3])
    without = predictor.predict(**base)
    with_identity = predictor.predict(**base, context=no_adjustment("NO_CANDIDATES"))
    assert without == with_identity
    for k in ("fairPriceP25", "fairPriceP50", "fairPriceP75", "decisionLow",
              "decisionHigh", "priceLevel"):
        assert without[k] == without[f"raw{k[0].upper()}{k[1:]}"] if k.startswith("fair") \
            else without[k] == without.get(f"raw{k[0].upper()}{k[1:]}", without[k])


@needs_artifact
def test_adjustment_scales_quantiles_and_recomputes_the_band(predictor):
    """Requirement 10."""
    base = dict(hotel_name="Zzz Unseen Property", current_price=9000.0, currency="INR",
                market="IN", booking_date=BOOKING, check_in_date=CHECK_IN,
                room_name="Deluxe Room", city="Mumbai", stars=4, rating=8.0,
                review_count=500, chain="NA", hotel_type_id=204, facility_ids=[1])
    raw = predictor.predict(**base)
    ctx = compute_context_factor([0.9, 0.9])
    adjusted = predictor.predict(**base, context=ctx)

    assert adjusted["contextAdjustmentApplied"] is True
    assert adjusted["contextAdjustmentFactor"] == 0.9
    # raw* is preserved untouched
    for k in ("rawFairPriceP25", "rawFairPriceP50", "rawFairPriceP75",
              "rawDecisionLow", "rawDecisionHigh"):
        assert adjusted[k] == raw[k]
    # and the published numbers are the scaled ones
    for k in ("P25", "P50", "P75"):
        assert adjusted[f"fairPrice{k}"] == round(raw[f"rawFairPrice{k}"] * 0.9, 2)
    assert adjusted["decisionLow"] < raw["rawDecisionLow"]
    assert adjusted["decisionHigh"] < raw["rawDecisionHigh"]
    # the band still brackets P50 with the business tolerance
    assert adjusted["decisionLow"] <= adjusted["fairPriceP50"] <= adjusted["decisionHigh"]


@needs_artifact
def test_verdict_uses_the_adjusted_band(predictor):
    """Requirement 12. A price that is FAIR against the raw band becomes
    EXPENSIVE once the candidates show this trip is pricing soft."""
    base = dict(hotel_name="Zzz Unseen Property", currency="INR", market="IN",
                booking_date=BOOKING, check_in_date=CHECK_IN, room_name="Deluxe Room",
                city="Mumbai", stars=4, rating=8.0, review_count=500,
                chain="NA", hotel_type_id=204, facility_ids=[1])
    probe_price = predictor.predict(**base, current_price=1.0)["rawFairPriceP50"]
    raw = predictor.predict(**base, current_price=probe_price)
    assert raw["priceLevel"] == "FAIR"

    adjusted = predictor.predict(**base, current_price=probe_price,
                                 context=compute_context_factor([CLAMP_LOW, CLAMP_LOW]))
    assert adjusted["priceLevel"] == "EXPENSIVE"
    assert adjusted["rawFairPriceP50"] == raw["rawFairPriceP50"]  # model unchanged
    assert adjusted["currentComparablePrice"] == raw["currentComparablePrice"]


@needs_artifact
def test_historical_target_is_never_adjusted(predictor):
    """Requirement 5. B2 is the hotel's own history, already CPI-rebased."""
    base = dict(hotel_name=HISTORICAL_HOTEL, current_price=20000.0, currency="INR",
                market="IN", booking_date=BOOKING, check_in_date=CHECK_IN,
                room_name="Deluxe Room", city="Mumbai")
    raw = predictor.predict(**base)
    assert raw["predictionSource"] == "HISTORICAL"

    forced = predictor.predict(**base, context=compute_context_factor([0.7, 0.7]))
    assert forced["contextAdjustmentApplied"] is False
    assert forced["contextAdjustmentFactor"] == 1.0
    assert forced["contextAdjustmentReason"] == REASON_HISTORICAL_EXCLUDED
    assert forced["temporalAdjustmentFactor"] == 1.3707
    for k in ("fairPriceP25", "fairPriceP50", "fairPriceP75",
              "decisionLow", "decisionHigh", "priceLevel"):
        assert forced[k] == raw[k]


@needs_artifact
def test_raw_and_final_agree_when_nothing_is_applied(predictor):
    got = predictor.predict(hotel_name="Zzz Unseen Property", current_price=9000.0,
                            currency="INR", market="IN", booking_date=BOOKING,
                            check_in_date=CHECK_IN, city="Mumbai", stars=4)
    assert got["contextAdjustmentApplied"] is False
    assert (got["fairPriceP25"], got["fairPriceP50"], got["fairPriceP75"],
            got["decisionLow"], got["decisionHigh"]) == \
           (got["rawFairPriceP25"], got["rawFairPriceP50"], got["rawFairPriceP75"],
            got["rawDecisionLow"], got["rawDecisionHigh"])


# =====================================================================
# 3. Endpoint behaviour
# =====================================================================
@needs_artifact
def test_candidate_hotels_absent_is_exactly_the_old_behaviour():
    """Requirement 13."""
    without, batch = _call(candidates=None)
    empty, _ = _call(candidates=[])
    batch.assert_not_awaited()
    assert without["contextAdjustmentApplied"] is False
    assert without["fairPriceP50"] == without["rawFairPriceP50"]
    for k in ("fairPriceP25", "fairPriceP50", "fairPriceP75", "decisionLow",
              "decisionHigh", "priceLevel"):
        assert without[k] == empty[k]


@needs_artifact
def test_five_candidates_probe_the_other_four_in_one_batch():
    """Requirements 1, 4, 14. Five in, the target dropped, ONE batched call."""
    candidates = [{"hotelId": TARGET, "hotelName": "Target"}] + \
                 [{"hotelId": f"c{i}", "hotelName": f"C{i}"} for i in range(1, 5)]
    batch = {f"c{i}": _probe(f"c{i}", 8000.0 + 100 * i) for i in range(1, 5)}
    body, batch_mock = _call(candidates=candidates, batch=batch)

    batch_mock.assert_awaited_once()                      # not N+1
    probed_ids = batch_mock.await_args.args[0]
    assert TARGET not in probed_ids                       # target excluded
    assert sorted(probed_ids) == ["c1", "c2", "c3", "c4"]
    assert body["validContextHotelCount"] == 4
    assert body["contextAdjustmentApplied"] is True
    assert body["contextAdjustmentBasis"] == CONTEXT_BASIS


@needs_artifact
def test_exactly_two_valid_context_candidates_is_enough():
    """Requirement 2."""
    candidates = [{"hotelId": f"c{i}", "hotelName": f"C{i}"} for i in range(1, 4)]
    batch = {"c1": _probe("c1", 8500.0), "c2": _probe("c2", 8700.0)}  # c3 unpriced
    body, _ = _call(candidates=candidates, batch=batch)
    assert body["validContextHotelCount"] == 2
    assert body["contextAdjustmentApplied"] is True


@needs_artifact
def test_one_valid_context_candidate_is_not_enough():
    """Requirement 3, end to end."""
    candidates = [{"hotelId": f"c{i}", "hotelName": f"C{i}"} for i in range(1, 4)]
    body, _ = _call(candidates=candidates, batch={"c1": _probe("c1", 8500.0)})
    assert body["contextAdjustmentApplied"] is False
    assert body["contextAdjustmentReason"] == REASON_INSUFFICIENT_CONTEXT
    assert body["fairPriceP50"] == body["rawFairPriceP50"]


@needs_artifact
def test_only_the_target_supplied_means_no_context():
    body, batch = _call(candidates=[{"hotelId": TARGET, "hotelName": "Target"}])
    batch.assert_not_awaited()
    assert body["contextAdjustmentReason"] == REASON_NO_CONTEXT_HOTELS
    assert body["contextAdjustmentApplied"] is False


@needs_artifact
def test_duplicate_candidate_ids_are_probed_once():
    candidates = [{"hotelId": "c1"}, {"hotelId": "c1"}, {"hotelId": "c2"}]
    batch = {"c1": _probe("c1", 8500.0), "c2": _probe("c2", 8700.0)}
    _, batch_mock = _call(candidates=candidates, batch=batch)
    assert batch_mock.await_args.args[0] == ["c1", "c2"]


@needs_artifact
def test_a_historical_candidate_never_enters_the_ml_ratios():
    """Requirement 6. Its quantiles come from a different baseline, so adding
    it must change neither the count nor the factor."""
    ml_only = {"c1": _probe("c1", 8500.0), "c2": _probe("c2", 8700.0)}
    without, _ = _call(candidates=[{"hotelId": "c1"}, {"hotelId": "c2"}],
                       batch=ml_only)
    with_hist, _ = _call(
        candidates=[{"hotelId": "c1"}, {"hotelId": "c2"}, {"hotelId": "hist"}],
        batch={**ml_only, "hist": _probe("hist", 21000.0, name=HISTORICAL_HOTEL)})

    assert without["validContextHotelCount"] == with_hist["validContextHotelCount"] == 2
    assert without["contextAdjustmentFactor"] == with_hist["contextAdjustmentFactor"]
    assert without["fairPriceP50"] == with_hist["fairPriceP50"]


@needs_artifact
def test_probe_failure_degrades_to_the_unadjusted_prediction():
    """Never fail the request: the raw band is still a valid answer."""
    candidates = [{"hotelId": "c1"}, {"hotelId": "c2"}]
    body, _ = _call(candidates=candidates, batch_raises=True)
    assert body["predictionAvailable"] is True
    assert body["contextAdjustmentApplied"] is False
    assert body["contextAdjustmentReason"] == REASON_PROBE_FAILED
    assert body["fairPriceP50"] == body["rawFairPriceP50"]


@needs_artifact
def test_no_rates_for_any_candidate_degrades_cleanly():
    body, _ = _call(candidates=[{"hotelId": "c1"}, {"hotelId": "c2"}], batch={})
    assert body["contextAdjustmentApplied"] is False
    assert body["contextAdjustmentReason"] == REASON_NO_CONTEXT_HOTELS


@needs_artifact
def test_historical_target_skips_the_context_probe_entirely():
    """Requirement 5 at the route: no adjustment means no LiteAPI cost."""
    candidates = [{"hotelId": "c1"}, {"hotelId": "c2"}]
    batch = {"c1": _probe("c1", 8500.0), "c2": _probe("c2", 8700.0)}
    body, batch_mock = _call(candidates=candidates, batch=batch,
                             target_name=HISTORICAL_HOTEL, target_price=20000.0)
    assert body["predictionSource"] == "HISTORICAL"
    batch_mock.assert_not_awaited()
    assert body["contextAdjustmentApplied"] is False
    assert body["temporalAdjustmentFactor"] == 1.3707


# =====================================================================
# 4. No circularity — requirements 4 and 10
# =====================================================================
@needs_artifact
def test_the_targets_own_price_cannot_move_its_own_band():
    """The strongest statement of no-circularity: change ONLY the target's live
    price and the factor, the raw band and the adjusted band must not move."""
    candidates = [{"hotelId": TARGET}, {"hotelId": "c1"}, {"hotelId": "c2"}]
    batch = {"c1": _probe("c1", 8500.0), "c2": _probe("c2", 8700.0)}

    cheap, _ = _call(candidates=candidates, batch=batch, target_price=3000.0)
    pricey, _ = _call(candidates=candidates, batch=batch, target_price=30000.0)

    assert cheap["contextAdjustmentFactor"] == pricey["contextAdjustmentFactor"]
    assert cheap["validContextHotelCount"] == pricey["validContextHotelCount"] == 2
    for k in ("rawFairPriceP50", "fairPriceP50", "decisionLow", "decisionHigh"):
        assert cheap[k] == pricey[k], f"{k} moved with the target's own price"
    # only the verdict differs, which is the point of the comparison
    assert cheap["priceLevel"] == "CHEAP" and pricey["priceLevel"] == "EXPENSIVE"


@needs_artifact
def test_target_is_dropped_even_when_the_agent_lists_it_first():
    candidates = [{"hotelId": TARGET}, {"hotelId": "c1"}, {"hotelId": "c2"}]
    _, batch_mock = _call(candidates=candidates,
                          batch={"c1": _probe("c1", 8500.0), "c2": _probe("c2", 8700.0)})
    assert TARGET not in batch_mock.await_args.args[0]


def test_route_source_never_feeds_the_target_into_the_ratios():
    """Static guard: the ratio loop must iterate context ids only."""
    import inspect
    src = inspect.getsource(routes._candidate_context)
    assert "seen, context_ids = {target}, []" in src
    assert "if hid not in seen" in src


# =====================================================================
# 5. The rest of the system is untouched
# =====================================================================
def test_liteapi_context_contract_matches_the_v3_probe():
    """Requirement 14. Same contract, or the ratios compare two different
    measurements. Constants are shared rather than re-typed."""
    from ml import india_liteapi_probe as p
    src = inspect_source(p.get_fair_price_probes_batch)
    assert '"hotelIds": ids' in src                       # batched, not per hotel
    assert "PROBE_ADULTS" in src and "PROBE_CURRENCY" in src
    assert "PROBE_GUEST_NATIONALITY" in src
    assert "next_day(check_in)" in src
    assert '"roomMapping": False' in src


def test_batch_profile_lookup_uses_comma_joined_ids():
    """Repeated hotelIds params silently return only the first hotel."""
    from ml import india_liteapi_probe as p
    assert '",".join(ids)' in inspect_source(p.fetch_hotel_profiles_batch)


def inspect_source(fn) -> str:
    import inspect
    return inspect.getsource(fn)


def test_team_price_advisor_route_and_contract_are_untouched():
    """Requirement 18.

    Deliberately does NOT invoke the advisor model: price_advisor_v1.joblib is
    pickled against scikit-learn 1.5.1 (as requirements.txt pins), so loading
    it under a different local scikit-learn fails for reasons that have nothing
    to do with candidate context - it fails identically on origin/main. What
    this change could actually break is the route and its request contract, so
    that is what is asserted.
    """
    from ml.schemas import PriceAdviceRequest

    registered = {r.path: getattr(r, "methods", set()) for r in routes.router.routes}
    assert "/api/ml/v2/price-advice" in registered
    assert "POST" in registered["/api/ml/v2/price-advice"]
    # ...and the V3 endpoints coexist with it rather than replacing it.
    assert "/api/ml/v2/hotel-price/by-hotel-id" in registered
    assert "/api/ml/predict-hotel-price" in registered

    # The request contract still validates a well-formed body unchanged.
    parsed = PriceAdviceRequest(city="Mumbai", check_in_date="2026-09-01",
                                check_out_date="2026-09-03",
                                number_of_guests=2, room_type="double")
    assert parsed.city == "Mumbai" and parsed.number_of_guests == 2
    assert "candidateHotels" not in PriceAdviceRequest.model_fields

    src = (Path(__file__).resolve().parent.parent / "ml" / "routes.py").read_text()
    assert '@router.post("/v2/price-advice"' in src
    assert "_advisor.advise(" in src
    assert "context" not in src.split("def price_advice")[1].split("def ")[0]


def test_pr10_stay_price_semantics_are_preserved():
    """Requirement 17: candidate context must not disturb the stay-total fix."""
    from agent.liteapi_client import nights_between
    assert nights_between("2026-08-23", "2026-08-25") == 2
    src = (Path(__file__).resolve().parent.parent / "agent" / "liteapi_client.py").read_text()
    assert 'sorted(results, key=lambda h: h["stayTotalPrice"])[:5]' in src
    assert '"averagePricePerNight": round(stay_total / nights, 2)' in src
