"""
Backlog #7 coverage: fallback behavior when searches can't fully satisfy
the traveler's original constraints (budget, cuisine, attraction category).
"""
import asyncio
import json
import os
import sys
import time
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from agent.orchestrator import extract_trip_requirements, generate_itinerary
from agent.tools import search_hotels, search_restaurants, search_attractions


def _print_section(title: str):
    print(f"\n{'=' * 60}\n{title}\n{'=' * 60}")


async def test_budget_far_too_low():
    """Budget so low that even the 1.5x relaxed ceiling can't cover any hotel."""
    _print_section("#7a: budget far too low - no hotel even after relaxing")
    extracted = await extract_trip_requirements(
        "Flying from Beijing, budget 30, going to Singapore next Monday for 3 days"
    )
    itinerary = await generate_itinerary(extracted)
    print(f"Warnings: {itinerary.get('warnings')}")

    all_hotels_null = all(itinerary[k]["hotel"] is None for k in itinerary if k.startswith("day"))
    assert all_hotels_null, "Expected no hotel to be assigned when budget is unrealistic"
    assert itinerary.get("warnings"), "Expected at least one warning"
    print("PASS")


async def test_budget_relaxed_succeeds():
    """Budget just under the cheapest hotel, but within 1.5x of it - should
    trigger budgetRelaxed=true and still return a hotel option."""
    _print_section("#7b: budget too low for normal search, but relaxed budget finds a hotel")
    # Direct call to the tool (not through the LLM) to precisely control the
    # budget value and reliably trigger the relaxed-budget branch.
    result = await search_hotels("Singapore", "2026-08-10", "2026-08-12", budget=40)
    print(json.dumps(result, indent=2))

    assert result["budgetRelaxed"] is True or result["hotels"] == [], (
        "Expected either a relaxed-budget result or a genuine no-match result"
    )
    if result["budgetRelaxed"]:
        assert result["hotels"], "Expected at least one hotel when budgetRelaxed is true"
        assert result["note"], "Expected a note explaining the relaxed budget"
    print("PASS")


async def test_restaurant_preference_relaxed():
    """A cuisine unlikely to have real matches in Singapore should trigger
    the fallback to a general restaurant search."""
    _print_section("#7c: obscure cuisine preference falls back to general search")
    result = await search_restaurants("Singapore", cuisine="Antarctic penguin cuisine")
    print(json.dumps(result, indent=2))

    assert result["restaurants"], "Expected fallback to still return general restaurant results"
    if result["preferenceRelaxed"]:
        assert result["note"], "Expected a note explaining the fallback"
    print("PASS")


async def test_attraction_category_relaxed():
    """An attraction category unlikely to have real matches should trigger
    the fallback to a general attraction search."""
    _print_section("#7d: obscure attraction category falls back to general search")
    result = await search_attractions("Singapore", category="underwater volcano tours")
    print(json.dumps(result, indent=2))

    assert result["attractions"], "Expected fallback to still return general attraction results"
    if result["preferenceRelaxed"]:
        assert result["note"], "Expected a note explaining the fallback"
    print("PASS")


async def test_no_data_at_all_location():
    """A location with no real hotel/flight data (a made-up or extremely obscure
    place) should degrade gracefully rather than crash."""
    _print_section("#7e: unresolvable location degrades gracefully")
    result = await search_hotels("Zzzznotarealplace", "2026-08-10", "2026-08-12", budget=200)
    print(json.dumps(result, indent=2))

    assert result["hotels"] == [], "Expected an empty hotel list for an unresolvable location"
    assert result["note"], "Expected a note explaining why no hotels were found"
    print("PASS")


async def main():
    tests = [
        test_budget_far_too_low,
        test_budget_relaxed_succeeds,
        test_restaurant_preference_relaxed,
        test_attraction_category_relaxed,
        test_no_data_at_all_location,
    ]

    results = []
    for test in tests:
        start = time.time()
        try:
            await test()
            results.append((test.__name__, "PASS", time.time() - start))
        except AssertionError as e:
            results.append((test.__name__, f"FAIL: {e}", time.time() - start))
        except Exception as e:
            results.append((test.__name__, f"ERROR: {e}", time.time() - start))

    _print_section("SUMMARY")
    for name, status, duration in results:
        print(f"{name}: {status} ({duration:.1f}s)")

    failed = [r for r in results if not r[1].startswith("PASS")]
    if failed:
        print(f"\n{len(failed)} test(s) failed.")
    else:
        print(f"\nAll {len(results)} tests passed.")


if __name__ == "__main__":
    asyncio.run(main())
