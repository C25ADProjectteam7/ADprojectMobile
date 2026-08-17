"""
Hotel price semantics — LiteAPI prices a STAY, not a night.

`offerRetailRate.amount` covers the whole check-in..check-out span, because the
rates request carries the real trip dates. It used to be surfaced as
`pricePerNight`, so a multi-night total looked like a nightly rate to the LLM,
to Spring and to the app - and the assembly prompt then multiplied it by nights
again when computing totalCost.

These tests pin the corrected contract and, just as importantly, pin the fact
that candidate ORDERING did not change: the old sort key was already this same
stay total, only misnamed.
"""
import asyncio
from unittest.mock import AsyncMock, patch

import pytest

from agent.liteapi_client import nights_between, search_hotels_by_coordinates

CHECK_IN = "2026-08-23"
ONE_NIGHT_OUT = "2026-08-24"
TWO_NIGHT_OUT = "2026-08-25"


def _hotels(*ids):
    return [{"id": h, "name": f"Hotel {h}", "city": "Delhi", "address": "addr",
             "latitude": 28.6, "longitude": 77.2, "rating": 8.0} for h in ids]


def _rates(**by_id):
    """hotelId -> stay total, in the shape _get_hotel_rates returns."""
    return {h: {"stayTotalPrice": amt, "offerId": f"offer-{h}"}
            for h, amt in by_id.items()}


def _search(hotels, rates, check_out, budget):
    """Sync wrapper - the project has no pytest-asyncio, and adding one would
    be an unrelated dependency change."""
    async def run():
        with patch("agent.liteapi_client._get_hotel_list", AsyncMock(return_value=hotels)), \
             patch("agent.liteapi_client._get_hotel_rates", AsyncMock(return_value=rates)), \
             patch("agent.distance_client.get_travel_times_minutes", AsyncMock(return_value=[])):
            return await search_hotels_by_coordinates(28.6, 77.2, CHECK_IN, check_out, budget)
    return asyncio.run(run())


# ----------------------------------------------------------- nights helper
@pytest.mark.parametrize("ci,co,expected", [
    ("2026-08-23", "2026-08-24", 1),
    ("2026-08-23", "2026-08-25", 2),
    ("2026-08-23", "2026-08-30", 7),
    ("2026-08-23", "2026-08-23", 1),      # same day -> floored at 1
    ("2026-08-25", "2026-08-23", 1),      # inverted -> floored at 1
    (None, None, 1),                       # unparseable -> floored at 1
])
def test_nights_between(ci, co, expected):
    assert nights_between(ci, co) == expected


# --------------------------------------------------------------- TEST 1/2
def test_one_night_stay_total_equals_nightly():
    r = _search(_hotels("A"), _rates(A=70.0), ONE_NIGHT_OUT, budget=1000)
    h = r["hotels"][0]
    assert h["numberOfNights"] == 1
    assert h["stayTotalPrice"] == 70.0
    assert h["averagePricePerNight"] == 70.0


def test_two_night_stay_total_is_halved_per_night():
    """The bug in one line: 70 for two nights is 35/night, not 70/night."""
    r = _search(_hotels("A"), _rates(A=70.0), TWO_NIGHT_OUT, budget=1000)
    h = r["hotels"][0]
    assert h["numberOfNights"] == 2
    assert h["stayTotalPrice"] == 70.0
    assert h["averagePricePerNight"] == 35.0


def test_legacy_price_per_night_key_is_now_really_per_night():
    """The key is kept for backward compatibility but no longer lies."""
    r = _search(_hotels("A"), _rates(A=70.59), TWO_NIGHT_OUT, budget=1000)
    h = r["hotels"][0]
    assert h["pricePerNight"] == h["averagePricePerNight"] == 35.3
    assert h["pricePerNight"] != h["stayTotalPrice"]


# ----------------------------------------------------------------- TEST 3
def test_budget_is_compared_against_the_stay_total():
    """Hotel budget is a whole-stay budget - unchanged behaviour."""
    r = _search(_hotels("A"), _rates(A=70.0), TWO_NIGHT_OUT, budget=100)
    assert [h["hotelId"] for h in r["hotels"]] == ["A"]
    assert r["budgetRelaxed"] is False


def test_budget_must_not_be_compared_against_the_nightly_average():
    """A 300 stay total over 2 nights averages 150/night. Against a 200 budget
    the STRICT filter must reject it (300 > 200); comparing the 150 nightly
    average instead would have let it pass as an exact match.

    It still surfaces through the relaxed fallback (300 <= 200*1.5), which is
    existing behaviour - the point is that budgetRelaxed is flagged rather than
    the hotel being silently presented as within budget."""
    r = _search(_hotels("A"), _rates(A=300.0), TWO_NIGHT_OUT, budget=200)
    assert r["budgetRelaxed"] is True, "stay total 300 wrongly passed a 200 budget"
    assert r["hotels"][0]["stayTotalPrice"] == 300.0
    assert r["hotels"][0]["averagePricePerNight"] == 150.0


def test_relaxed_fallback_still_uses_stay_total():
    r = _search(_hotels("A"), _rates(A=280.0), TWO_NIGHT_OUT, budget=200)
    assert r["budgetRelaxed"] is True
    assert r["hotels"][0]["stayTotalPrice"] == 280.0


# ----------------------------------------------------------------- TEST 5
def test_candidate_ordering_is_unchanged_by_this_fix():
    """A=80, B=60, C=100 over the same dates must still rank B, A, C."""
    r = _search(_hotels("A", "B", "C"),
                      _rates(A=80.0, B=60.0, C=100.0), TWO_NIGHT_OUT, budget=1000)
    assert [h["hotelId"] for h in r["hotels"]] == ["B", "A", "C"]
    assert [h["stayTotalPrice"] for h in r["hotels"]] == [60.0, 80.0, 100.0]


def test_ordering_by_stay_total_not_by_nightly_average():
    """All candidates share the dates, so the two orderings coincide - this
    pins that the sort key is the stay total, matching the previous build."""
    r = _search(_hotels("A", "B"), _rates(A=90.0, B=70.0),
                      TWO_NIGHT_OUT, budget=1000)
    assert [h["hotelId"] for h in r["hotels"]] == ["B", "A"]
    assert r["hotels"][0]["averagePricePerNight"] == 35.0


def test_result_carries_currency_and_offer_id():
    r = _search(_hotels("A"), _rates(A=70.0), TWO_NIGHT_OUT, budget=1000)
    h = r["hotels"][0]
    assert h["currency"] == "USD"
    assert h["offerId"] == "offer-A"
    assert h["hotelId"] == "A"


# ----------------------------------------------------------------- TEST 4
def test_total_cost_adds_the_stay_total_once():
    """flight 200 + hotel stay total 70 over 2 nights = 270, never 340."""
    r = _search(_hotels("A"), _rates(A=70.0), TWO_NIGHT_OUT, budget=1000)
    hotel = r["hotels"][0]
    flight = 200.0
    correct_total = flight + hotel["stayTotalPrice"]
    double_counted = flight + hotel["stayTotalPrice"] * hotel["numberOfNights"]
    assert correct_total == 270.0
    assert double_counted == 340.0
    # the per-night figure times nights reconstructs the stay total exactly
    assert hotel["averagePricePerNight"] * hotel["numberOfNights"] == pytest.approx(70.0)


def test_assembly_prompts_forbid_multiplying_the_stay_total():
    """The prompts drive totalCost, so the wording is part of the contract."""
    from pathlib import Path
    src = (Path(__file__).resolve().parent.parent / "agent" / "orchestrator.py").read_text()
    assert 'hotel "stayTotalPrice" (added ONCE' in src
    assert "never multiply stayTotalPrice by nights" in src
    assert "never multiplied by nights" in src
    assert "hotel(per-night x nights)" not in src, "old double-counting formula still present"
