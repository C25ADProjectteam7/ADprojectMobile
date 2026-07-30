"""
Agent tool definitions — all external tools the agent can call
Defined as Function Calling JSON schemas; agent decides which to invoke

Available tools:
- search_flights(origin, destination, date)         → query Amadeus
- search_hotels(city, check_in, check_out, budget)  → query Amadeus
- search_restaurants(city, cuisine, price_level)    → query Google Places
- search_attractions(city, category)                → query Google Places
- get_price_prediction(flight_or_hotel, date)       → call ML price model
- allocate_budget(destination, days, total_budget)  → call ML budget model
- book_flight(flight_id, passenger_info)            → simulated booking
- book_hotel(hotel_id, guest_info)                  → simulated booking
"""

# TODO: define JSON Schema for each tool (name, description, parameters)
# TODO: implement the actual Python functions that each tool calls
# TODO: integrate Amadeus API, Google Places API, and ML model calls
