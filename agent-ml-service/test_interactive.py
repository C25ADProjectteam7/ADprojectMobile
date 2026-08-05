"""
Interactive end-to-end test - YOU type real input at the terminal, just like
a real user would talk to the Agent. All API calls are real (DeepSeek,
Duffel, LiteAPI, Google Places, Frankfurter) - nothing is mocked or
pre-scripted.

Run: python test_interactive.py
Type your request when prompted. If info is missing, the Agent will ask a
follow-up question - keep typing your answers until it generates an
itinerary. Then you'll be asked to confirm before it books anything for real
(test-mode bookings, no real money moves).
"""
import asyncio
import json
from agent.orchestrator import extract_trip_requirements, generate_itinerary
from agent.tools import book_flight, book_hotel


async def main():
    print("=" * 60)
    print("Travel Assistant - type your request below")
    print("=" * 60)

    conversation_so_far = ""

    while True:
        user_input = input("\nYou: ").strip()
        if not user_input:
            continue
        if user_input.lower() in ("quit", "exit"):
            print("Goodbye!")
            return

        conversation_so_far += (" " if conversation_so_far else "") + user_input
        extracted = await extract_trip_requirements(conversation_so_far)

        if extracted["missingFields"]:
            print(f"\nAgent: {extracted['clarifyingQuestion']}")
            continue  # loop back and wait for the next thing you type

        break  # all required fields present, move on

    print("\nAgent: Great, let me put together an itinerary for you...\n")
    itinerary = await generate_itinerary(extracted)

    if "error" in itinerary:
        print(f"Agent: Sorry, something went wrong: {itinerary['error']}")
        return

    day_keys = sorted(k for k in itinerary if k.startswith("day"))
    for k in day_keys:
        day = itinerary[k]
        print(f"\n--- {k} ({day.get('date')}) ---")
        if day.get("flight"):
            f = day["flight"]
            print(f"  Flight: {f['flightNumber']} {f['origin']}->{f['destination']} "
                  f"{f['departureTime']} - ${f['price']:.2f}")
        if day.get("hotel"):
            h = day["hotel"]
            print(f"  Hotel: {h['name']} - ${h['pricePerNight']:.2f}/night")
        for meal in ("breakfast", "lunch", "dinner"):
            if day.get(meal):
                print(f"  {meal.capitalize()}: {day[meal]['name']}")
        if day.get("attraction"):
            print(f"  Attraction: {day['attraction']['name']}")

    print(f"\nTotal estimated cost: ${itinerary['totalCost']:.2f} USD "
          f"(~S${itinerary.get('totalCostSGD', 'N/A')})")
    if itinerary.get("warnings"):
        print("Notes: " + " | ".join(itinerary["warnings"]))

    # ---- Real confirmation gate - YOU decide ----
    confirm = input("\nAgent: Would you like me to book this? (yes/no): ").strip().lower()
    if confirm not in ("yes", "y"):
        print("Agent: Okay, no problem. Let me know if you'd like any changes.")
        return

    passenger_name = input("Agent: What name should I book under? ").strip()
    passenger_dob = input("Agent: Date of birth (YYYY-MM-DD)? ").strip()
    email = input("Agent: Contact email for the booking? ").strip()

    day1 = itinerary["day1"]
    if not day1.get("flight") or not day1.get("hotel"):
        print("Agent: Sorry, I'm missing flight or hotel data to complete the booking.")
        return

    print("\nAgent: Booking your flight...")
    flight_result = await book_flight(
        day1["flight"]["offerId"], passenger_name, passenger_dob,
        origin=day1["flight"]["origin"], destination=day1["flight"]["destination"],
        date=day1["date"],
    )
    print(json.dumps(flight_result, indent=2))

    if not flight_result.get("success"):
        print(f"Agent: I couldn't book your flight: {flight_result.get('error')}")
        return

    print("\nAgent: Booking your hotel...")
    hotel_result = await book_hotel(day1["hotel"]["offerId"], passenger_name, email)
    print(json.dumps(hotel_result, indent=2))

    if hotel_result.get("success"):
        print(f"\nAgent: All booked! Flight reference: {flight_result.get('bookingReference')}, "
              f"Hotel booking ID: {hotel_result.get('bookingId')}. Have a great trip!")
    else:
        print(f"\nAgent: Your flight is booked, but the hotel booking failed: {hotel_result.get('error')}")


if __name__ == "__main__":
    asyncio.run(main())