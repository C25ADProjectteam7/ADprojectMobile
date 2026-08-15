"""
Agent tool definitions - all external tools the agent can call
Defined as Function Calling JSON schemas; the agent decides which to invoke
"""
from agent.duffel_client import search_flights as duffel_search_flights
from agent.duffel_client import resolve_city_to_coordinates
from agent.duffel_client import book_flight_with_retry
from agent.liteapi_client import search_hotels_by_coordinates
from agent.liteapi_client import book_hotel_with_retry
from agent.google_places_client import search_restaurants as places_search_restaurants
from agent.google_places_client import search_attractions as places_search_attractions
from agent.google_places_client import resolve_city_center


async def _city_coordinates(city: str):
    """Coordinates for city-wide searches. Prefers the true city center
    (via Google Places) - Duffel resolves cities to their FIRST AIRPORT's
    coordinates, which biases hotel results toward airport hotels and can
    also skew restaurants/attractions. Falls back to airport coordinates
    when the city-center lookup fails."""
    center = await resolve_city_center(city)
    if center:
        return center
    return await resolve_city_to_coordinates(city)


def get_tool_schemas() -> list[dict]:
    """Backlog #5/#6/#8: JSON schemas for the tools the agent can call."""
    return [
        {
            "type": "function",
            "function": {
                "name": "search_flights",
                "description": "Search available flights between two airports on a given date. Both origin and destination MUST be 3-letter IATA airport or city codes (e.g. 'BJS', 'SIN'), never a plain city name. May return an empty list if no flights are available for that route/date - this is a valid result, not an error.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "origin": {"type": "string", "description": "3-letter IATA code of the departure city/airport, e.g. 'BJS'"},
                        "destination": {"type": "string", "description": "3-letter IATA code of the arrival city/airport, e.g. 'SIN'"},
                        "date": {"type": "string", "description": "Departure date, YYYY-MM-DD"},
                    },
                    "required": ["origin", "destination", "date"],
                },
            },
        },
        {
            "type": "function",
            "function": {
                "name": "search_hotels",
                "description": "Search available hotels in a city within a budget for given check-in/check-out dates. If nothing fits the budget, automatically retries with a relaxed budget and flags this (budgetRelaxed=true) - check this flag and mention it to the traveler.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "city": {"type": "string", "description": "Plain city name in ENGLISH (e.g. 'Singapore', 'Beijing'), regardless of what language was used elsewhere in the conversation"},
                        "check_in": {"type": "string", "description": "YYYY-MM-DD"},
                        "check_out": {"type": "string", "description": "YYYY-MM-DD"},
                        "budget": {"type": "number", "description": "Max total budget for hotel"},
                    },
                    "required": ["city", "check_in", "check_out", "budget"],
                },
            },
        },
        {
            "type": "function",
            "function": {
                "name": "search_restaurants",
                "description": "Search restaurants in a city. Use the cuisine parameter to reflect any relevant food preference; leave it empty if none.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "city": {"type": "string", "description": "Plain city name in ENGLISH (e.g. 'Singapore', 'Beijing'), regardless of what language was used elsewhere in the conversation"},
                        "cuisine": {"type": "string", "description": "Cuisine or food preference, e.g. 'seafood'. Leave empty if none specified."},
                    },
                    "required": ["city"],
                },
            },
        },
        {
            "type": "function",
            "function": {
                "name": "search_attractions",
                "description": "Search tourist attractions in a city. Use the category parameter to reflect any relevant interest; leave it empty if none.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "city": {"type": "string", "description": "Plain city name in ENGLISH (e.g. 'Singapore', 'Beijing'), regardless of what language was used elsewhere in the conversation"},
                        "category": {"type": "string", "description": "Attraction category/interest, e.g. 'museums'. Leave empty if none specified."},
                    },
                    "required": ["city"],
                },
            },
        },
        {
            "type": "function",
            "function": {
                "name": "book_flight",
                "description": "Books a flight using a specific offerId from a previous search_flights result. IMPORTANT: this performs a real (test-mode) booking transaction - only call this when the traveler has EXPLICITLY confirmed they want to book this specific flight, never proactively or speculatively.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "offer_id": {"type": "string", "description": "The offerId field from a previous search_flights result"},
                        "passenger_name": {"type": "string", "description": "Full name of the passenger"},
                        "passenger_dob": {"type": "string", "description": "Passenger date of birth, YYYY-MM-DD"},
                    },
                    "required": ["offer_id", "passenger_name", "passenger_dob"],
                },
            },
        },
        {
            "type": "function",
            "function": {
                "name": "book_hotel",
                "description": "Books a hotel room using a specific offerId from a previous search_hotels result. This is a two-step process (prebook then book) handled internally. IMPORTANT: this performs a real (test-mode) booking transaction - only call this when the traveler has EXPLICITLY confirmed they want to book this specific hotel, never proactively or speculatively.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "offer_id": {"type": "string", "description": "The offerId field from a previous search_hotels result"},
                        "guest_name": {"type": "string", "description": "Full name of the guest staying in the room"},
                        "email": {"type": "string", "description": "Contact email for the booking"},
                    },
                    "required": ["offer_id", "guest_name", "email"],
                },
            },
        },
    ]


def get_search_tool_schemas() -> list[dict]:
    """Schemas safe for itinerary planning: no transactional tools are exposed."""
    return [
        schema for schema in get_tool_schemas()
        if schema["function"]["name"].startswith("search_")
    ]


def get_booking_tool_schemas() -> list[dict]:
    """Schemas reserved for a future explicit booking-confirmation workflow."""
    return [
        schema for schema in get_tool_schemas()
        if schema["function"]["name"].startswith("book_")
    ]


async def search_flights(origin: str, destination: str, date: str) -> list[dict]:
    return await duffel_search_flights(origin, destination, date)


async def search_hotels(city: str, check_in: str, check_out: str, budget: float,
                         guest_nationality: str = "SG") -> dict:
    """Backlog #5/#7: real hotel search via LiteAPI, with automatic budget-relaxation
    fallback. guest_nationality is a business-logic parameter bound by the
    orchestrator - never supplied by the LLM."""
    coords = await _city_coordinates(city)
    if not coords:
        return {"hotels": [], "budgetRelaxed": False, "note": f"Could not resolve location for '{city}'."}
    lat, lng = coords
    return await search_hotels_by_coordinates(lat, lng, check_in, check_out, budget, guest_nationality)


async def search_restaurants(city: str, cuisine: str = "") -> dict:
    coords = await _city_coordinates(city)
    if not coords:
        return {"restaurants": [], "preferenceRelaxed": False, "note": f"Could not resolve location for '{city}'."}
    lat, lng = coords
    return await places_search_restaurants(city, lat, lng, cuisine)


async def search_attractions(city: str, category: str = "") -> dict:
    coords = await _city_coordinates(city)
    if not coords:
        return {"attractions": [], "preferenceRelaxed": False, "note": f"Could not resolve location for '{city}'."}
    lat, lng = coords
    return await places_search_attractions(city, lat, lng, category)


async def book_flight(offer_id: str, passenger_name: str, passenger_dob: str,
                       origin: str = None, destination: str = None, date: str = None) -> dict:
    """Backlog #8/#9: books a real (test-mode) flight via Duffel, with automatic
    retry/recovery for expired offers and transient network errors.
    origin/destination/date enable re-search if the offer has expired -
    the orchestrator should pass these from the original search context."""
    return await book_flight_with_retry(offer_id, passenger_name, passenger_dob, origin, destination, date)


async def book_hotel(offer_id: str, guest_name: str, email: str,
                      latitude: float = None, longitude: float = None,
                      check_in: str = None, check_out: str = None,
                      budget: float = None, guest_nationality: str = "SG") -> dict:
    """Backlog #8/#9: books a real (test-mode) hotel via LiteAPI, with automatic
    retry/recovery for expired offers and transient network errors."""
    name_parts = guest_name.strip().split(" ", 1)
    first_name = name_parts[0]
    last_name = name_parts[1] if len(name_parts) > 1 else name_parts[0]

    return await book_hotel_with_retry(
        offer_id, first_name, last_name, email, first_name, last_name, email,
        latitude, longitude, check_in, check_out, budget, guest_nationality
    )

TOOL_FUNCTIONS = {
    "search_flights": search_flights,
    "search_hotels": search_hotels,
    "search_restaurants": search_restaurants,
    "search_attractions": search_attractions,
    "book_flight": book_flight,
    "book_hotel": book_hotel,
}

# Planning endpoints use this allow-list, so prompt injection or model mistakes
# cannot invoke real booking operations.
SEARCH_TOOL_FUNCTIONS = {
    name: TOOL_FUNCTIONS[name]
    for name in ("search_flights", "search_hotels", "search_restaurants", "search_attractions")
}

BOOKING_TOOL_FUNCTIONS = {
    name: TOOL_FUNCTIONS[name]
    for name in ("book_flight", "book_hotel")
}
