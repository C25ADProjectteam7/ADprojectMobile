import asyncio
import json
from agent.tools import book_flight, book_hotel


async def test_flight_retry_with_bad_offer():
    print("=== Flight booking retry (bad offer, with re-search context) ===")
    result = await book_flight(
        "off_0000000000INVALID", "Li Ming", "1988-05-20",
        origin="PEK", destination="SIN", date="2026-08-25"
    )
    print(json.dumps(result, indent=2))


async def test_hotel_retry_with_bad_offer():
    print("\n=== Hotel booking retry (bad offer, with re-search context) ===")
    result = await book_hotel(
        "invalid_offer_id_xyz", "Li Ming", "test@example.com",
        latitude=1.359288, longitude=103.910629,
        check_in="2026-08-25", check_out="2026-08-27", budget=200
    )
    print(json.dumps(result, indent=2))


async def main():
    await test_flight_retry_with_bad_offer()
    await test_hotel_retry_with_bad_offer()


if __name__ == "__main__":
    asyncio.run(main())