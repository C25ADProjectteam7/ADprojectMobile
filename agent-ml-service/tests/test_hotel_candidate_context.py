"""
Hotel candidate context — the identities of the hotels that were on offer
alongside the one the itinerary actually chose.

These are injected programmatically from our own search data, never copied by
the assembly LLM: it is only ever shown the top 3 candidates, and it already
drops or corrupts long verbatim fields (the reason _ensure_offer_ids exists).

What these tests pin:
  * the context lands on the normal, non-debug path
  * it carries IDENTITIES ONLY - no prices ever cross over
  * hotel selection, ranking and the top-5 ordering are untouched
"""
from agent.orchestrator import (
    MAX_CANDIDATE_CONTEXT_HOTELS,
    _ensure_hotel_candidate_context,
    _hotel_candidates_from_call,
)


def _search_call(*hotels):
    """The {"hotels": [...]} shape search_hotels actually returns."""
    return {
        "args": {},
        "results": {
            "hotels": [
                {
                    "hotelId": h,
                    "name": f"Hotel {h}",
                    "stayTotalPrice": 100.0 + i,
                    "averagePricePerNight": 50.0 + i,
                    "numberOfNights": 2,
                    "currency": "USD",
                    "offerId": f"offer-{h}",
                }
                for i, h in enumerate(hotels)
            ],
            "note": None,
        },
    }


def _itinerary(selected_name, days=1):
    return {
        f"day{n}": {"date": "2026-08-23", "hotel": {"name": selected_name}}
        for n in range(1, days + 1)
    }


# --------------------------------------------------------------- extraction
def test_candidates_are_identity_only():
    got = _hotel_candidates_from_call(_search_call("A", "B"))
    assert got == [{"hotelId": "A", "hotelName": "Hotel A"},
                   {"hotelId": "B", "hotelName": "Hotel B"}]
    for c in got:
        assert set(c) == {"hotelId", "hotelName"}


def test_no_price_field_ever_crosses_over():
    """The Agent's USD stay totals must not travel with the identities - the
    ML service re-probes every hotel on its own one-night INR contract."""
    for c in _hotel_candidates_from_call(_search_call("A", "B", "C")):
        for banned in ("stayTotalPrice", "averagePricePerNight", "pricePerNight",
                       "price", "currency", "offerId", "numberOfNights"):
            assert banned not in c


def test_candidates_are_capped_at_five():
    got = _hotel_candidates_from_call(_search_call(*"ABCDEFGH"))
    assert len(got) == MAX_CANDIDATE_CONTEXT_HOTELS == 5
    assert [c["hotelId"] for c in got] == list("ABCDE")


def test_search_ordering_is_preserved_verbatim():
    """search_hotels ranks by stay total; the recorded order must match it."""
    got = _hotel_candidates_from_call(_search_call("C", "A", "B"))
    assert [c["hotelId"] for c in got] == ["C", "A", "B"]


def test_malformed_and_partial_entries_are_skipped():
    call = {"results": {"hotels": [
        {"hotelId": "A", "name": "Hotel A"},
        {"hotelId": None, "name": "No id"},
        {"hotelId": "C", "name": "   "},
        {"hotelId": "D"},
        "not-a-dict",
        {"hotelId": "E", "name": "Hotel E"},
    ]}}
    assert _hotel_candidates_from_call(call) == [
        {"hotelId": "A", "hotelName": "Hotel A"},
        {"hotelId": "E", "hotelName": "Hotel E"},
    ]


def test_failed_tool_call_shape_is_tolerated():
    """A failed search stores {"error": {...}} instead of a result list."""
    assert _hotel_candidates_from_call({"results": {"error": {"msg": "boom"}}}) == []
    assert _hotel_candidates_from_call({"results": "nonsense"}) == []
    assert _hotel_candidates_from_call({}) == []


# --------------------------------------------------------------- injection
def test_context_is_attached_to_the_selected_hotel():
    itin = _itinerary("Hotel B")
    _ensure_hotel_candidate_context(itin, {"search_hotels": [_search_call("A", "B", "C")]})
    hotel = itin["day1"]["hotel"]
    assert hotel["hotelId"] == "B"
    assert [c["hotelId"] for c in hotel["candidateHotels"]] == ["A", "B", "C"]


def test_the_selected_hotel_is_included_in_the_recorded_list():
    """Exclusion is the ML service's job and is tested there; the Agent records
    what the search returned, unfiltered."""
    itin = _itinerary("Hotel A")
    _ensure_hotel_candidate_context(itin, {"search_hotels": [_search_call("A", "B")]})
    assert "A" in [c["hotelId"] for c in itin["day1"]["hotel"]["candidateHotels"]]


def test_every_day_of_the_stay_gets_the_context():
    itin = _itinerary("Hotel A", days=3)
    _ensure_hotel_candidate_context(itin, {"search_hotels": [_search_call("A", "B")]})
    for n in (1, 2, 3):
        assert itin[f"day{n}"]["hotel"]["hotelId"] == "A"
        assert len(itin[f"day{n}"]["hotel"]["candidateHotels"]) == 2


def test_llm_supplied_values_are_overwritten():
    """Same contract as _ensure_offer_ids: our own data always wins."""
    itin = _itinerary("Hotel B")
    itin["day1"]["hotel"]["hotelId"] = "WRONG-ID"
    itin["day1"]["hotel"]["candidateHotels"] = [{"hotelId": "GARBAGE", "hotelName": "x"}]
    _ensure_hotel_candidate_context(itin, {"search_hotels": [_search_call("A", "B")]})
    assert itin["day1"]["hotel"]["hotelId"] == "B"
    assert [c["hotelId"] for c in itin["day1"]["hotel"]["candidateHotels"]] == ["A", "B"]


def test_unknown_hotel_name_is_left_alone():
    itin = _itinerary("Some Hotel The Search Never Returned")
    _ensure_hotel_candidate_context(itin, {"search_hotels": [_search_call("A", "B")]})
    assert "candidateHotels" not in itin["day1"]["hotel"]


def test_no_search_data_is_a_no_op():
    itin = _itinerary("Hotel A")
    _ensure_hotel_candidate_context(itin, {})
    _ensure_hotel_candidate_context(itin, {"search_hotels": []})
    assert "candidateHotels" not in itin["day1"]["hotel"]


def test_non_day_keys_and_hotel_less_days_are_ignored():
    itin = {"totalCost": 1200.0, "warnings": ["x"],
            "day1": {"date": "2026-08-23", "hotel": None},
            "day2": {"date": "2026-08-24", "hotel": {"name": "Hotel A"}}}
    _ensure_hotel_candidate_context(itin, {"search_hotels": [_search_call("A")]})
    assert itin["totalCost"] == 1200.0
    assert itin["day1"]["hotel"] is None
    assert itin["day2"]["hotel"]["candidateHotels"] == [{"hotelId": "A", "hotelName": "Hotel A"}]


# ------------------------------------------------------------ modify flow
def test_modify_without_a_new_search_carries_the_context_forward():
    existing = _itinerary("Hotel A")
    existing["day1"]["hotel"]["hotelId"] = "A"
    existing["day1"]["hotel"]["candidateHotels"] = [
        {"hotelId": "A", "hotelName": "Hotel A"}, {"hotelId": "B", "hotelName": "Hotel B"}]
    updated = _itinerary("Hotel A")
    _ensure_hotel_candidate_context(updated, {"search_hotels": []}, existing)
    assert [c["hotelId"] for c in updated["day1"]["hotel"]["candidateHotels"]] == ["A", "B"]
    assert updated["day1"]["hotel"]["hotelId"] == "A"


def test_a_fresh_search_wins_over_the_carried_forward_copy():
    """"find me a cheaper hotel" re-searches; the new candidates are the
    current trip's context, so the stale list must not survive."""
    existing = _itinerary("Hotel A")
    existing["day1"]["hotel"]["candidateHotels"] = [{"hotelId": "OLD", "hotelName": "Hotel OLD"}]
    updated = _itinerary("Hotel D")
    _ensure_hotel_candidate_context(updated, {"search_hotels": [_search_call("D", "E")]}, existing)
    assert [c["hotelId"] for c in updated["day1"]["hotel"]["candidateHotels"]] == ["D", "E"]
    assert updated["day1"]["hotel"]["hotelId"] == "D"


def test_hotel_replacement_rebinds_the_context_to_the_new_hotel():
    """After a replacement the context must describe the NEW hotel's search."""
    itin = _itinerary("Hotel E")
    _ensure_hotel_candidate_context(itin, {"search_hotels": [_search_call("D", "E", "F")]})
    assert itin["day1"]["hotel"]["hotelId"] == "E"
    assert [c["hotelId"] for c in itin["day1"]["hotel"]["candidateHotels"]] == ["D", "E", "F"]


def test_the_last_search_call_wins_when_several_ran():
    itin = _itinerary("Hotel Z")
    _ensure_hotel_candidate_context(itin, {"search_hotels": [
        _search_call("A", "B"), _search_call("Y", "Z")]})
    assert [c["hotelId"] for c in itin["day1"]["hotel"]["candidateHotels"]] == ["Y", "Z"]


# ------------------------------------------------- selection is untouched
def test_injection_does_not_change_the_chosen_hotel():
    itin = _itinerary("Hotel C")
    before = itin["day1"]["hotel"]["name"]
    _ensure_hotel_candidate_context(itin, {"search_hotels": [_search_call("A", "B", "C")]})
    assert itin["day1"]["hotel"]["name"] == before == "Hotel C"


def test_injection_only_adds_keys_and_never_removes_them():
    itin = _itinerary("Hotel A")
    itin["day1"]["hotel"].update({"stayTotalPrice": 300.0, "averagePricePerNight": 150.0,
                                  "numberOfNights": 2, "offerId": "keep-me", "address": "addr"})
    _ensure_hotel_candidate_context(itin, {"search_hotels": [_search_call("A")]})
    hotel = itin["day1"]["hotel"]
    assert hotel["stayTotalPrice"] == 300.0 and hotel["averagePricePerNight"] == 150.0
    assert hotel["numberOfNights"] == 2 and hotel["offerId"] == "keep-me"
    assert hotel["address"] == "addr"


def test_runs_on_the_normal_path_not_only_under_debug():
    """rawSearchData is debug-gated; this context must not be."""
    import inspect
    from agent import orchestrator
    src = inspect.getsource(orchestrator._assemble_and_validate_itinerary)
    assert "_ensure_hotel_candidate_context(itinerary, gathered, existing_itinerary)" in src
    assert "if debug" not in src
