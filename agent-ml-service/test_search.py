import asyncio
import json
from agent.orchestrator import extract_trip_requirements, search_travel_options


async def main():
    extracted = await extract_trip_requirements(
        "I'm flying from Beijing, budget 2000, going to Singapore next Monday for a 3-day business trip"
    )
    print("Extracted requirements:")
    print(json.dumps(extracted, indent=2))

    if extracted.get("missingFields"):
        print("\nCannot proceed to search - missing fields:", extracted["missingFields"])
        return

    result = await search_travel_options(extracted)
    print("\nSearch results:")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    asyncio.run(main())