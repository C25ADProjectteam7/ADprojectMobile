import asyncio
import json
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from agent.duffel_client import search_flights, book_flight, cancel_flight_booking
from agent.liteapi_client import search_hotels_by_coordinates, book_hotel_with_retry, cancel_hotel_booking


async def test_cancel_flight():
    print("=== Cancel a flight booking ===")
    flights = await search_flights("PEK", "SIN", "2026-09-01")
    offer_id = flights[0]["offerId"]
    booking = await book_flight(offer_id, "Chen Jie", "1995-07-10")
    print("Booked:", json.dumps(booking, indent=2))

    if booking["success"]:
        cancellation = await cancel_flight_booking(booking["orderId"])
        print("Cancelled:", json.dumps(cancellation, indent=2))


async def test_cancel_hotel():
    print("\n=== Cancel a hotel booking ===")
    search_result = await search_hotels_by_coordinates(
        1.359288, 103.910629, "2026-09-01", "2026-09-03", budget=200
    )
    offer_id = search_result["hotels"][0]["offerId"]
    booking = await book_hotel_with_retry(
        offer_id, "Chen", "Jie", "test@example.com", "Chen", "Jie"
    )
    print("Booked:", json.dumps(booking, indent=2))

    if booking["success"]:
        cancellation = await cancel_hotel_booking(booking["bookingId"])
        print("Cancelled:", json.dumps(cancellation, indent=2))


async def main():
    await test_cancel_flight()
    await test_cancel_hotel()


if __name__ == "__main__":
    asyncio.run(main())
