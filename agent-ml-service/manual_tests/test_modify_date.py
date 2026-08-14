import asyncio
import json
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from agent.orchestrator import extract_trip_requirements, generate_itinerary, modify_itinerary


async def main():
    extracted = await extract_trip_requirements(
        "Flying from Beijing, budget 2000, going to Singapore next Monday for 3 days"
    )
    original = await generate_itinerary(extracted)
    updated = await modify_itinerary(original, "Can you push the whole trip back by one day?")

    print("=== Original ===")
    print("day1 date:", original["day1"]["date"])
    print("day1 flight departureTime:", original["day1"]["flight"]["departureTime"] if original["day1"]["flight"] else None)

    print("\n=== Updated ===")
    for k in sorted(kk for kk in updated if kk.startswith("day")):
        day = updated[k]
        flight = day.get("flight")
        print(f"{k}: date={day.get('date')}, flight_departure={flight.get('departureTime') if flight else None}")

    print("\n=== Updated day1 full flight object ===")
    print(json.dumps(updated["day1"].get("flight"), indent=2))
    print("\n=== Updated day1 full hotel object ===")
    print(json.dumps(updated["day1"].get("hotel"), indent=2))


if __name__ == "__main__":
    asyncio.run(main())
