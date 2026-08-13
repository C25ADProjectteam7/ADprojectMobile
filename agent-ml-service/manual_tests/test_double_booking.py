import asyncio
import json
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from agent.duffel_client import search_flights, book_flight
from agent.liteapi_client import search_hotels_by_coordinates, prebook_hotel, book_hotel


async def test_double_flight_booking():
    print("=== Double flight booking (same offer twice) ===")
    flights = await search_flights("PEK", "SIN", "2026-08-28")
    if not flights:
        print("No flights found.")
        return
    offer_id = flights[0]["offerId"]

    print("First booking (should succeed):")
    first = await book_flight(offer_id, "Wang Fang", "1992-03-15")
    print(json.dumps(first, indent=2))

    print("\nSecond booking with SAME offer (should fail - already consumed):")
    second = await book_flight(offer_id, "Wang Fang", "1992-03-15")
    print(json.dumps(second, indent=2))


async def test_double_hotel_prebook():
    print("\n=== Double hotel prebook (same offer twice) ===")
    search_result = await search_hotels_by_coordinates(
        1.359288, 103.910629, "2026-08-28", "2026-08-30", budget=200
    )
    if not search_result["hotels"]:
        print("No hotels found.")
        return
    offer_id = search_result["hotels"][0]["offerId"]

    print("First prebook (should succeed):")
    first = await prebook_hotel(offer_id)
    print(json.dumps({k: v for k, v in first.items() if k != "raw"}, indent=2))

    print("\nSecond prebook with SAME offer (may fail or may just re-verify - need to check):")
    second = await prebook_hotel(offer_id)
    print(json.dumps({k: v for k, v in second.items() if k != "raw"}, indent=2))

async def test_double_hotel_book():
    print("\n=== Double hotel BOOK (same prebookId twice) ===")
    search_result = await search_hotels_by_coordinates(
        1.359288, 103.910629, "2026-08-28", "2026-08-30", budget=200
    )
    offer_id = search_result["hotels"][0]["offerId"]
    prebook_result = await prebook_hotel(offer_id)
    prebook_id = prebook_result["raw"]["data"]["prebookId"]

    print("First book (should succeed):")
    first = await book_hotel(prebook_id, "Wang", "Fang", "test@example.com", "Wang", "Fang")
    print(json.dumps(first, indent=2))

    print("\nSecond book with SAME prebookId (should fail - already consumed):")
    second = await book_hotel(prebook_id, "Wang", "Fang", "test@example.com", "Wang", "Fang")
    print(json.dumps(second, indent=2))

async def main():
    await test_double_flight_booking()
    await test_double_hotel_prebook()
    await test_double_hotel_book()


if __name__ == "__main__":
    asyncio.run(main())
