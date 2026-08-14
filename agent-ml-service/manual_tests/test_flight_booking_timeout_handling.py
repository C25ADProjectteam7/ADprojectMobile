"""
Fast, no-network regression test for duffel_client.py's flight-booking
timeout handling (agent-ml-service/agent/duffel_client.py).

Covers a real bug: a timeout while actually creating the Duffel order (as
opposed to the earlier, read-only offer-details lookup) used to be swallowed
by book_flight() into an ordinary "failed" result. By the time
book_flight_with_retry() recorded that as an attempt, it was indistinguishable
from any other failure, so _summarize_flight_failure() reported the generic
"validation error, double-check passenger details" message instead of the
safety-critical "status unknown, do not blindly retry" one - and the retry
loop had no way to know it should stop rather than retry (risking a
duplicate real order). Fixed by letting that specific timeout propagate out
of book_flight() so book_flight_with_retry() can catch it directly and
return immediately, mirroring liteapi_client.py's book_hotel_with_retry.

Run (from agent-ml-service/): python manual_tests/test_flight_booking_timeout_handling.py
"""
import asyncio
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import httpx
from unittest.mock import AsyncMock, patch

from agent import duffel_client

FAKE_OFFER = {
    "passengers": [{"id": "pas_00001"}],
    "total_amount": "500.00",
    "total_currency": "USD",
}


async def test_order_creation_timeout_returns_unsafe_to_retry_message_without_retrying():
    with patch.object(duffel_client, "get_offer_details", AsyncMock(return_value=FAKE_OFFER)), \
         patch.object(httpx.AsyncClient, "post", AsyncMock(side_effect=httpx.TimeoutException("boom"))):
        result = await duffel_client.book_flight_with_retry(
            "off_test", "Li Ming", "1988-05-20", max_retries=2
        )

    assert result["success"] is False
    assert result.get("requiresManualBooking") is True, f"Expected requiresManualBooking=True, got: {result}"
    assert "could not be confirmed" in result["error"], \
        f"Expected the status-unknown message, got: {result['error']}"
    assert "duplicate booking" in result["nextSteps"], \
        f"Expected the duplicate-booking warning, got: {result['nextSteps']}"

    attempts = result["attempts"]
    assert len(attempts) == 1, \
        f"An order-creation timeout must never be retried (order may already exist server-side), got: {attempts}"
    assert attempts[0]["outcome"] == "book_timeout_unsafe_to_retry", f"Unexpected attempts: {attempts}"


async def test_offer_lookup_timeout_stays_a_plain_retryable_failure():
    # A timeout on the read-only offer-details lookup happens BEFORE any
    # order is created, so it must NOT be treated as unsafe-to-retry - it's
    # an ordinary failure (get_offer_details already retried internally via
    # retry_on_timeout before this raises).
    with patch("asyncio.sleep", AsyncMock()), \
         patch.object(httpx.AsyncClient, "get", AsyncMock(side_effect=httpx.TimeoutException("boom"))):
        result = await duffel_client.book_flight_with_retry(
            "off_test", "Li Ming", "1988-05-20", max_retries=0
        )

    assert result["success"] is False
    assert result.get("requiresManualBooking") is True
    attempts = result["attempts"]
    assert len(attempts) == 1
    assert attempts[0]["outcome"] == "failed", f"Unexpected attempts: {attempts}"
    assert attempts[0]["codes"] == ["timeout"], f"Unexpected attempts: {attempts}"


def main():
    tests = [
        test_order_creation_timeout_returns_unsafe_to_retry_message_without_retrying,
        test_offer_lookup_timeout_stays_a_plain_retryable_failure,
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
