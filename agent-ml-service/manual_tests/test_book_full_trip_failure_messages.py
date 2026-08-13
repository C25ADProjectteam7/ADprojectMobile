"""
Fast, no-network regression test for book_full_trip()'s top-level failure
message (agent-ml-service/agent/orchestrator.py).

Covers a real gap found while auditing what a caller sees after
book_flight_with_retry() exhausts its retries: book_flight_with_retry()
itself already computes a specific, human-readable reason + nextSteps via
_summarize_flight_failure() (e.g. "price no longer available, search again"
vs "timed out, don't blindly retry - contact support"), and that survives
intact all the way to the HTTP response, nested under
result["flightResult"]. But book_full_trip()'s own top-level "message"/
"nextSteps" - the more obvious place a caller would look first - used to be
a fixed generic string that discarded that specific reason. Fixed by having
the top level quote flightResult's own error/nextSteps instead of a
separate hardcoded string, mirroring the hotel-failure branch which already
did this correctly.

Run (from agent-ml-service/): python manual_tests/test_book_full_trip_failure_messages.py
"""
import asyncio
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from unittest.mock import AsyncMock, patch

from agent import duffel_client, orchestrator


async def test_top_level_message_reflects_specific_flight_failure_reason():
    always_expired = AsyncMock(return_value={
        "success": False,
        "error": [{"code": "offer_no_longer_available", "message": "Could not retrieve offer: 422"}],
    })
    with patch.object(duffel_client, "book_flight", always_expired), \
         patch.object(duffel_client, "search_flights", AsyncMock(return_value=[{"offerId": "off_fresh_but_still_bad"}])), \
         patch.object(orchestrator, "_is_itinerary_stale", return_value=False):
        result = await orchestrator.book_full_trip(
            itinerary={"generatedAt": "2026-08-13T00:00:00+00:00"},
            flight_offer_id="off_original", hotel_offer_id="hotel_off",
            passenger_name="Test Person", passenger_dob="1990-01-01", email="test@example.com",
            origin="PEK", destination="SIN", date_str="2026-09-15",
        )

    assert result["success"] is False
    assert result["stage"] == "flight"
    specific_reason = result["flightResult"]["error"]
    specific_next_steps = result["flightResult"]["nextSteps"]

    assert specific_reason in result["message"], (
        f"Top-level message must include the specific failure reason, not just a generic "
        f"string. reason={specific_reason!r} message={result['message']!r}"
    )
    assert result["nextSteps"] == specific_next_steps, (
        f"Top-level nextSteps must match flightResult's own specific guidance, "
        f"got {result['nextSteps']!r} vs {specific_next_steps!r}"
    )
    # No leftover formatting artifact from concatenating two already-punctuated strings.
    assert ".." not in result["message"], f"Double-period artifact in message: {result['message']!r}"


def main():
    tests = [test_top_level_message_reflects_specific_flight_failure_reason]
    failed = []
    for test in tests:
        try:
            asyncio.run(test())
            print(f"PASS: {test.__name__}")
        except AssertionError as exc:
            failed.append(test.__name__)
            print(f"FAIL: {test.__name__}: {exc}")

    if failed:
        raise SystemExit(f"\n{len(failed)} test(s) failed: {failed}")
    print(f"\nAll {len(tests)} tests passed.")


if __name__ == "__main__":
    main()
