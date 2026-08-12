import asyncio
import json
from agent.tools import search_flights, book_flight, search_hotels, book_hotel


async def test_flight_booking():
    print("=== Flight booking ===")
    flights = await search_flights("PEK", "SIN", "2026-08-25")
    if not flights:
        print("No flights found.")
        return

    offer_id = flights[0]["offerId"]
    result = await book_flight(offer_id, "Li Ming", "1988-05-20")
    print(json.dumps(result, indent=2))


async def test_hotel_booking():
    print("\n=== Hotel booking ===")
    hotels_result = await search_hotels("Singapore", "2026-08-25", "2026-08-27", budget=200)
    if not hotels_result["hotels"]:
        print("No hotels found.")
        return

    offer_id = hotels_result["hotels"][0]["offerId"]
    result = await book_hotel(offer_id, "Li Ming", "test@example.com")
    print(json.dumps(result, indent=2))


async def main():
    await test_flight_booking()
    await test_hotel_booking()


if __name__ == "__main__":
    asyncio.run(main())