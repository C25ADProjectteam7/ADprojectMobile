import asyncio
import json
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from agent.liteapi_client import search_hotels_by_coordinates, prebook_hotel, book_hotel


async def test_client_reference_dedup():
    print("=== Testing clientReference dedup ===")
    search_result = await search_hotels_by_coordinates(
        1.359288, 103.910629, "2026-09-05", "2026-09-07", budget=200
    )
    offer_id = search_result["hotels"][0]["offerId"]
    prebook_result = await prebook_hotel(offer_id)
    prebook_id = prebook_result["raw"]["data"]["prebookId"]

    same_client_ref = "test-dedup-key-001"

    print("First book (should succeed):")
    first = await book_hotel(
        prebook_id, "Wang", "Fang", "test@example.com", "Wang", "Fang",
        client_reference=same_client_ref
    )
    print(json.dumps(first, indent=2))

    print("\nSecond book with SAME clientReference (does LiteAPI now reject it?):")
    second = await book_hotel(
        prebook_id, "Wang", "Fang", "test@example.com", "Wang", "Fang",
        client_reference=same_client_ref
    )
    print(json.dumps(second, indent=2))

    if first.get("bookingId") == second.get("bookingId"):
        print("\n>>> RESULT: clientReference successfully prevented duplicate booking")
    else:
        print("\n>>> RESULT: clientReference did NOT prevent duplicate booking - still created 2 separate bookings")


if __name__ == "__main__":
    asyncio.run(test_client_reference_dedup())
