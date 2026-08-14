import asyncio
import json
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from agent.duffel_client import book_flight_with_retry
from agent.liteapi_client import book_hotel_with_retry


async def test_flight_failure_message():
    print("=== Flight booking failure message (no valid re-search context) ===")
    # No origin/destination/date provided, so re-search fallback can't kick in -
    # this forces it to exhaust retries and hit the final failure message.
    result = await book_flight_with_retry(
        "off_totally_invalid_xyz", "Test Person", "1990-01-01", max_retries=1
    )
    print(json.dumps(result, indent=2))


async def test_hotel_failure_message():
    print("\n=== Hotel booking failure message (no valid re-search context) ===")
    result = await book_hotel_with_retry(
        "invalid_offer_xyz", "Test", "Person", "test@example.com", "Test", "Person",
        max_retries=1
    )
    print(json.dumps(result, indent=2))


async def main():
    await test_flight_failure_message()
    await test_hotel_failure_message()


if __name__ == "__main__":
    asyncio.run(main())
