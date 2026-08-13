"""
Fast, no-network regression test for a real bug: duffel_client.book_flight()
used to hardcode "traveler@example.com" as every passenger's email on the
real Duffel order-creation request, regardless of the traveler's actual
email address (which book_full_trip() receives as a required parameter and
correctly threads it through to the hotel booking, but never to the flight
booking). Every real flight booking's confirmation email would go to that
placeholder instead of the traveler. Fixed by threading the real email
through book_full_trip() -> book_flight_with_retry() -> book_flight().

Run (from agent-ml-service/): python manual_tests/test_flight_booking_uses_real_email.py
"""
import asyncio
import json
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from unittest.mock import AsyncMock, MagicMock, patch

import httpx

from agent import duffel_client, orchestrator

FAKE_OFFER = {
    "passengers": [{"id": "pas_00001"}],
    "total_amount": "500.00",
    "total_currency": "USD",
}


def _fake_order_response(captured_payloads):
    async def fake_post(self, url, headers=None, json=None, **kwargs):
        captured_payloads.append(json)
        response = MagicMock()
        response.status_code = 200
        response.json.return_value = {
            "data": {"booking_reference": "ABC123", "id": "ord_test", "total_amount": "500.00"}
        }
        return response
    return fake_post


async def test_book_flight_with_retry_sends_real_email_to_duffel():
    captured_payloads = []
    with patch.object(duffel_client, "get_offer_details", AsyncMock(return_value=FAKE_OFFER)), \
         patch.object(httpx.AsyncClient, "post", _fake_order_response(captured_payloads)):
        result = await duffel_client.book_flight_with_retry(
            "off_test", "Li Ming", "1988-05-20", email="real.traveler@gmail.com"
        )

    assert result["success"] is True, f"Expected success, got: {result}"
    assert len(captured_payloads) == 1
    sent_email = captured_payloads[0]["data"]["passengers"][0]["email"]
    assert sent_email == "real.traveler@gmail.com", (
        f"Expected the real traveler email to reach Duffel's order payload, got: {sent_email!r}"
    )


async def test_book_full_trip_forwards_the_real_email_to_the_flight_leg():
    captured_payloads = []
    with patch.object(duffel_client, "get_offer_details", AsyncMock(return_value=FAKE_OFFER)), \
         patch.object(httpx.AsyncClient, "post", _fake_order_response(captured_payloads)), \
         patch.object(orchestrator, "_is_itinerary_stale", return_value=False), \
         patch("agent.liteapi_client.book_hotel_with_retry", AsyncMock(return_value={"success": False, "error": "n/a"})):
        await orchestrator.book_full_trip(
            itinerary={"generatedAt": "2026-08-13T00:00:00+00:00"},
            flight_offer_id="off_test", hotel_offer_id="hotel_off",
            passenger_name="Li Ming", passenger_dob="1988-05-20",
            email="real.traveler@gmail.com",
        )

    # The flight order creation is always the first POST book_full_trip makes
    # (before any hotel/cancellation calls, which may add further captured
    # payloads of their own here since the hotel leg was deliberately made to
    # fail above) - so index [0] is specifically the flight order payload.
    assert captured_payloads, "Expected at least one captured POST payload (the flight order creation)"
    sent_email = captured_payloads[0]["data"]["passengers"][0]["email"]
    assert sent_email == "real.traveler@gmail.com", (
        f"book_full_trip's email parameter must reach the actual Duffel order payload, got: {sent_email!r}"
    )


def main():
    tests = [
        test_book_flight_with_retry_sends_real_email_to_duffel,
        test_book_full_trip_forwards_the_real_email_to_the_flight_leg,
    ]
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
