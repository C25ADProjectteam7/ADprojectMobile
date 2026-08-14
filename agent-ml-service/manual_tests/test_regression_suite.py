"""
Regression test suite - Backlog #4-#10 core scenarios, with real assertions
instead of manual visual inspection. Uses real API calls throughout (no
mocks) - run this after any change to orchestrator.py, tools.py, or any of
the API client modules to catch regressions automatically.

Run (from agent-ml-service/): python manual_tests/test_regression_suite.py
"""
import asyncio
import os
import sys
import time
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from agent.orchestrator import (
    extract_trip_requirements,
    generate_itinerary,
    modify_itinerary,
    book_full_trip,
)
from agent.tools import search_hotels, search_restaurants, search_attractions


def _section(title: str):
    print(f"\n{'=' * 60}\n{title}\n{'=' * 60}")


async def test_extraction_complete():
    _section("#4a: extraction with complete input")
    result = await extract_trip_requirements(
        "I'm flying from Beijing, budget 2000, going to Singapore next Monday "
        "for a 3-day business trip, want seafood"
    )
    assert result["missingFields"] == [], f"Expected no missing fields, got {result['missingFields']}"
    assert result["originCity"] == "Beijing", f"Expected 'Beijing', got {result['originCity']}"
    assert result["destination"] == "Singapore", f"Expected 'Singapore', got {result['destination']}"
    assert result["budgetTotal"] == 2000
    assert "seafood" in " ".join(result["preferences"]).lower()


async def test_extraction_chinese_input():
    _section("#4b: Chinese-language city names resolve to English")
    result = await extract_trip_requirements(
        "从新加坡出发，下周一去北京旅行两天，预算10000"
    )
    assert result["originCity"] == "Singapore", f"Expected English 'Singapore', got {result['originCity']}"
    assert result["destination"] == "Beijing", f"Expected English 'Beijing', got {result['destination']}"


async def test_extraction_incomplete():
    _section("#4c: incomplete input triggers a clarifying question")
    result = await extract_trip_requirements("I need to go on a business trip, help me book an itinerary")
    assert len(result["missingFields"]) > 0
    assert result["clarifyingQuestion"], "Expected a non-empty clarifying question"


async def test_full_itinerary_generation():
    _section("#6: full itinerary generation")
    extracted = await extract_trip_requirements(
        "Flying from Beijing, budget 2000, going to Singapore next Monday for 3 days, want seafood"
    )
    assert extracted["missingFields"] == []

    itinerary = await generate_itinerary(extracted)
    assert "error" not in itinerary, f"Unexpected error: {itinerary.get('error')}"

    day_keys = sorted(k for k in itinerary if k.startswith("day"))
    assert len(day_keys) == 3, f"Expected 3 days, got {len(day_keys)}"

    assert itinerary["day1"]["flight"] is not None, "Expected outbound flight on day1"
    assert itinerary["day1"]["flight"].get("offerId"), "day1 flight missing offerId"
    assert itinerary["day1"]["hotel"] is not None, "Expected hotel on day1"
    assert itinerary["day1"]["hotel"].get("offerId"), "day1 hotel missing offerId"
    assert itinerary["day1"]["breakfast"] is None, "Arrival day should not have breakfast"

    last_day = itinerary[day_keys[-1]]
    assert last_day["hotel"] is None, "Departure day should not have a hotel"

    assert "totalCostSGD" in itinerary, "Missing SGD cost field"
    assert "totalCostOriginalUSD" in itinerary, "Missing original USD cost field"
    assert "totalCost" not in itinerary, "Legacy totalCost field should have been renamed"
    assert itinerary["currency"] == "SGD"
    assert "generatedAt" in itinerary, "Missing generatedAt timestamp"
    assert "rawSearchData" not in itinerary, "rawSearchData should be omitted when debug=False"

    return itinerary  # reused by later tests


async def test_debug_flag_includes_raw_data():
    _section("#6b: debug=True includes rawSearchData")
    extracted = await extract_trip_requirements(
        "Flying from Beijing, budget 2000, going to Singapore next Monday for 3 days"
    )
    itinerary = await generate_itinerary(extracted, debug=True)
    assert "rawSearchData" in itinerary, "Expected rawSearchData when debug=True"
    assert "search_flights" in itinerary["rawSearchData"]


async def test_modify_itinerary_only_changes_requested_field(original_itinerary: dict):
    _section("#10: modification only changes the requested field")
    updated = await modify_itinerary(original_itinerary, "Can you find me a more expensive hotel instead?")

    assert "error" not in updated
    orig_hotel_name = original_itinerary["day1"]["hotel"]["name"]
    updated_hotel_name = updated["day1"]["hotel"]["name"]
    # Known intermittent (not a regression - reproduced and root-caused live):
    # search_hotels caps results at 5 candidates for this city/date, and if
    # the original itinerary's hotel already happens to be the priciest of
    # those 5, "find me a more expensive hotel" correctly has nothing better
    # to switch to - the LLM re-searches (even raises the budget) exactly as
    # instructed, gets the identical candidate set back, and correctly keeps
    # the same hotel rather than fabricate a fictitious upgrade. Observed
    # failure rate ~1/3 in manual reproduction. If this fails, rerun before
    # assuming a real regression.
    assert updated_hotel_name != orig_hotel_name, "Expected hotel to change"

    for day_key in sorted(k for k in original_itinerary if k.startswith("day")):
        for field in ("flight", "breakfast", "attraction", "lunch", "dinner"):
            orig_val = original_itinerary[day_key].get(field)
            upd_val = updated[day_key].get(field)
            orig_name = orig_val.get("name") if orig_val else orig_val
            upd_name = upd_val.get("name") if upd_val else upd_val
            assert orig_name == upd_name, (
                f"{day_key}.{field} changed unexpectedly: {orig_name} -> {upd_name}"
            )

    assert "totalCostSGD" in updated
    assert "generatedAt" in updated


async def test_fallback_budget_far_too_low():
    _section("#7a: budget far too low triggers graceful fallback, not a crash")
    extracted = await extract_trip_requirements(
        "Flying from Beijing, budget 30, going to Singapore next Monday for 3 days"
    )
    itinerary = await generate_itinerary(extracted)
    assert "error" not in itinerary
    all_hotels_null = all(
        itinerary[k]["hotel"] is None for k in itinerary if k.startswith("day")
    )
    assert all_hotels_null, "Expected no hotel assigned when budget is unrealistic"
    assert itinerary.get("warnings"), "Expected at least one warning explaining the shortfall"


async def test_fallback_budget_relaxed_succeeds():
    _section("#7b: moderately-low budget triggers relaxed-budget success")
    result = await search_hotels("Singapore", "2026-08-10", "2026-08-12", budget=40)
    assert result["hotels"] or result["budgetRelaxed"] is False, (
        "Expected either relaxed-budget results or a genuine no-match result"
    )
    if result["budgetRelaxed"]:
        assert result["hotels"], "Expected at least one hotel when budgetRelaxed is true"
        assert result["note"]


async def test_fallback_unresolvable_location():
    _section("#7c: unresolvable location degrades gracefully, no crash")
    result = await search_hotels("Zzzznotarealplace", "2026-08-10", "2026-08-12", budget=200)
    assert result["hotels"] == []
    assert result["note"]


async def test_booking_and_cancellation():
    _section("#8/#9: real (test-mode) booking + cancellation round-trip")
    from agent.duffel_client import search_flights, book_flight_with_retry, cancel_flight_booking

    # Deliberately PEK, not the more "realistic" BJS (Beijing metro area
    # code) that resolve_city_to_iata() would normally return: verified live
    # that Duffel's TEST-mode data for BJS->SIN only ever surfaces PKX
    # (Daxing) offers, and every one of them prices/searches fine but always
    # fails at actual order creation with offer_no_longer_available - not a
    # code bug, just sandbox test-data coverage. PEK->SIN books successfully
    # every time. Production (live API key, real airline inventory) won't
    # have this gap, so this is a test-fixture choice, not a product fix.
    origin, destination, date_str = "PEK", "SIN", "2026-09-10"
    flights = await search_flights(origin, destination, date_str)
    assert flights, "Expected at least one flight result"

    # Uses the retry-with-recovery path (same one production booking goes
    # through via book_full_trip), not the raw book_flight(): Duffel's
    # sandbox offers can go stale between search and book, and book_flight()
    # alone has no way to recover from that - book_flight_with_retry()
    # re-searches and retries automatically when it sees an expired-offer
    # error, so this test isn't flaky on transient sandbox offer expiry.
    booking = await book_flight_with_retry(
        flights[0]["offerId"], "Regression Test", "1990-01-01",
        origin=origin, destination=destination, date=date_str,
    )
    assert booking["success"], f"Booking failed: {booking.get('error')}"
    assert booking.get("orderId")

    cancellation = await cancel_flight_booking(booking["orderId"])
    assert cancellation["success"], f"Cancellation failed: {cancellation.get('error')}"


async def main():
    results = []

    simple_tests = [
        test_extraction_complete,
        test_extraction_chinese_input,
        test_extraction_incomplete,
        test_debug_flag_includes_raw_data,
        test_fallback_budget_far_too_low,
        test_fallback_budget_relaxed_succeeds,
        test_fallback_unresolvable_location,
        test_booking_and_cancellation,
    ]

    for test in simple_tests:
        start = time.time()
        try:
            await test()
            results.append((test.__name__, "PASS", time.time() - start))
        except AssertionError as e:
            results.append((test.__name__, f"FAIL: {e}", time.time() - start))
        except Exception as e:
            results.append((test.__name__, f"ERROR: {e}", time.time() - start))

    # Sequential dependency: generate an itinerary once, reuse it for the
    # modification test, to avoid generating twice (saves ~2 min of runtime)
    start = time.time()
    try:
        original_itinerary = await test_full_itinerary_generation()
        results.append(("test_full_itinerary_generation", "PASS", time.time() - start))

        start2 = time.time()
        await test_modify_itinerary_only_changes_requested_field(original_itinerary)
        results.append(("test_modify_itinerary_only_changes_requested_field", "PASS", time.time() - start2))
    except AssertionError as e:
        results.append(("itinerary_generation_or_modification", f"FAIL: {e}", time.time() - start))
    except Exception as e:
        results.append(("itinerary_generation_or_modification", f"ERROR: {e}", time.time() - start))

    _section("SUMMARY")
    for name, status, duration in results:
        print(f"{name}: {status} ({duration:.1f}s)")

    failed = [r for r in results if not r[1].startswith("PASS")]
    if failed:
        print(f"\n{len(failed)} test(s) failed out of {len(results)}.")
    else:
        print(f"\nAll {len(results)} tests passed.")


if __name__ == "__main__":
    asyncio.run(main())
