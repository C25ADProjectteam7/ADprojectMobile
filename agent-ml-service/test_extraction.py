import asyncio
import json
from agent.orchestrator import extract_trip_requirements


async def main():
    test_cases = [
        "I'm flying from Beijing, budget 2000, going to Singapore next Monday for a 3-day business trip, want a hotel in the city center",
        "I need to go on a business trip, help me book an itinerary",  # severely incomplete, tests clarifying logic
        "Flying out of Shanghai, going to Beijing from the 15th to the 18th of next month, budget 5000",  # missing preferences, check it stays empty
    ]

    for i, case in enumerate(test_cases, 1):
        print(f"\n--- Test {i}: {case} ---")
        result = await extract_trip_requirements(case)
        print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    asyncio.run(main())