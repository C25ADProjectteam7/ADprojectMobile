import asyncio
import json
from agent.orchestrator import extract_trip_requirements, generate_itinerary


async def main():
    extracted = await extract_trip_requirements(
        "I'm flying from Beijing, budget 2000, going to Singapore next Monday for a 3-day business trip, want seafood"
    )
    print("Extracted requirements:")
    print(json.dumps(extracted, indent=2))

    if extracted.get("missingFields"):
        print("Missing fields:", extracted["missingFields"])
        return

    itinerary = await generate_itinerary(extracted)
    print("\nGenerated itinerary:")
    print(json.dumps(itinerary, indent=2))


if __name__ == "__main__":
    asyncio.run(main())