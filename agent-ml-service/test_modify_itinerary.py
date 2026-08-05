import asyncio
import json
from agent.orchestrator import extract_trip_requirements, generate_itinerary, modify_itinerary


async def main():
    extracted = await extract_trip_requirements(
        "Flying from Beijing, budget 2000, going to Singapore next Monday for 3 days, want seafood"
    )
    original = await generate_itinerary(extracted)
    updated = await modify_itinerary(original, "Can you find me a nicer, more expensive hotel instead?")

    print("=== Comparing day-by-day ===")
    for day_key in sorted(k for k in original if k.startswith("day")):
        orig_day = original[day_key]
        upd_day = updated.get(day_key, {})
        for field in ["flight", "hotel", "breakfast", "attraction", "lunch", "dinner"]:
            orig_val = orig_day.get(field)
            upd_val = upd_day.get(field)
            orig_name = orig_val.get("name") if orig_val else None
            upd_name = upd_val.get("name") if upd_val else None
            changed = "CHANGED" if orig_name != upd_name else "same"
            print(f"{day_key}.{field}: {orig_name} -> {upd_name}  [{changed}]")

    print(f"\nOriginal totalCost: {original.get('totalCost')}")
    print(f"Updated totalCost: {updated.get('totalCost')}")


if __name__ == "__main__":
    asyncio.run(main())