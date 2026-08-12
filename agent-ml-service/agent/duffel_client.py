"""
Duffel API client - flight search and place resolution
Replaces Amadeus flight search (Amadeus self-service API portal was decommissioned
in July 2026, see team discussion).
Duffel uses a request/response flow: submit an Offer Request describing the trip,
get back a list of Offers (priced itineraries) from partner airlines.
Docs: https://duffel.com/docs/api/overview/welcome

Note: Duffel Stays (hotel search) was evaluated but requires sales approval,
not available for self-service accounts ("This feature is not enabled for your
account"). Hotel search uses LiteAPI instead - see agent/liteapi_client.py.
The place-resolution helpers below (IATA code, coordinates, country code) are
still used to support LiteAPI's coordinate-based hotel search.
"""
import httpx
import config
from agent.http_utils import retry_on_timeout

DUFFEL_VERSION = "v2"  # required header, pins the API schema version

# Cache of city-name -> raw Duffel Places API response ("data" list).
# City/airport metadata (IATA codes, coordinates, country codes) is static
# for the lifetime of this process, so once a city has been looked up, all
# three resolve_* functions below can reuse the same result instead of each
# making its own identical API call.
_place_cache: dict[str, list] = {}


def _duffel_headers() -> dict:
    return {
        "Authorization": f"Bearer {config.DUFFEL_API_TOKEN}",
        "Duffel-Version": DUFFEL_VERSION,
        "Content-Type": "application/json",
    }


@retry_on_timeout(max_attempts=3)
async def _get_place_data(city_name: str) -> list:
    """Shared, cached lookup backing resolve_city_to_iata/coordinates/
    country_code. Cache key is the city name as given (not normalized) -
    callers passing inconsistent capitalization/spacing for the same city
    will produce separate cache entries, which is a minor inefficiency but
    not a correctness issue."""
    if city_name in _place_cache:
        return _place_cache[city_name]

    async with httpx.AsyncClient(timeout=15.0) as client:
        response = await client.get(
            f"{config.DUFFEL_BASE_URL}/places/suggestions",
            headers=_duffel_headers(),
            params={"query": city_name},
        )
        response.raise_for_status()
        data = response.json()

    places = data.get("data", [])
    _place_cache[city_name] = places
    return places


async def resolve_city_to_iata(city_name: str) -> str | None:
    """Backlog #5 support: converts a free-text city name (e.g. 'Singapore')
    into an IATA code (e.g. 'SIN'). Returns None if no match is found."""
    places = await _get_place_data(city_name)
    if not places:
        return None

    for place in places:
        if place.get("type") == "city":
            return place["iata_code"]

    return places[0]["iata_code"]


async def resolve_city_to_coordinates(city_name: str) -> tuple[float, float] | None:
    """Backlog #5 support: resolves a city name to (latitude, longitude).
    City-level records have null lat/lng themselves - coordinates must be
    taken from one of the city's associated airports."""
    places = await _get_place_data(city_name)

    for place in places:
        if place.get("type") == "city":
            airports = place.get("airports") or []
            if airports:
                first_airport = airports[0]
                return first_airport["latitude"], first_airport["longitude"]

    for place in places:
        if place.get("type") == "airport" and place.get("latitude") is not None:
            return place["latitude"], place["longitude"]

    return None


async def resolve_city_to_country_code(city_name: str) -> str | None:
    """Resolves a city name to its ISO country code (e.g. 'Singapore' -> 'SG').
    Used to derive guestNationality for hotel pricing."""
    places = await _get_place_data(city_name)

    for place in places:
        if place.get("type") == "city" and place.get("iata_country_code"):
            return place["iata_country_code"]

    if places and places[0].get("iata_country_code"):
        return places[0]["iata_country_code"]
    return None


@retry_on_timeout(max_attempts=3)
async def get_offer_details(offer_id: str) -> dict:
    """Backlog #8 support: retrieves full offer details (including the
    passenger IDs and exact total amount required for booking)."""
    async with httpx.AsyncClient(timeout=20.0) as client:
        response = await client.get(
            f"{config.DUFFEL_BASE_URL}/air/offers/{offer_id}",
            headers=_duffel_headers(),
        )
        response.raise_for_status()
        return response.json()["data"]


@retry_on_timeout(max_attempts=3)
async def search_flights(origin: str, destination: str, date: str) -> list[dict]:
    """Searches flights between two airports on a given date (direct or with
    connections). origin/destination must be IATA airport or city codes."""
    payload = {
        "data": {
            "slices": [
                {"origin": origin, "destination": destination, "departure_date": date}
            ],
            "passengers": [{"type": "adult"}],
            "cabin_class": "economy",
        }
    }

    async with httpx.AsyncClient(timeout=30.0) as client:
        response = await client.post(
            f"{config.DUFFEL_BASE_URL}/air/offer_requests",
            headers=_duffel_headers(),
            json=payload,
            params={"return_offers": "true"},
        )
        response.raise_for_status()
        data = response.json()

    offers = data["data"].get("offers", [])
    results = []
    for offer in offers[:5]:
        first_slice = offer["slices"][0]
        segments = first_slice["segments"]
        first_segment = segments[0]
        last_segment = segments[-1]

        results.append({
            "offerId": offer["id"],  # NEW: needed for booking in #8
            "flightNumber": f"{first_segment['operating_carrier']['iata_code']}{first_segment['operating_carrier_flight_number']}",
            "origin": first_segment["origin"]["iata_code"],
            "destination": last_segment["destination"]["iata_code"],
            "departureTime": first_segment["departing_at"],
            "arrivalTime": last_segment["arriving_at"],
            "stops": len(segments) - 1,
            "price": float(offer["total_amount"]),
        })
    return sorted(results, key=lambda f: f["price"])


async def book_flight(offer_id: str, passenger_name: str, passenger_dob: str) -> dict:
    """Backlog #8: creates a real Duffel order in test mode."""
    try:
        offer = await get_offer_details(offer_id)
    except httpx.HTTPStatusError as e:
        return {
            "success": False,
            "error": [{"code": "offer_no_longer_available",
                       "message": f"Could not retrieve offer: {e.response.status_code}"}],
        }
    except httpx.TimeoutException:
        return {
            "success": False,
            "error": [{"code": "timeout", "message": "Timed out retrieving offer details"}],
        }

    passenger_id = offer["passengers"][0]["id"]
    total_amount = offer["total_amount"]
    total_currency = offer["total_currency"]

    name_parts = passenger_name.strip().split(" ", 1)
    given_name = name_parts[0]
    family_name = name_parts[1] if len(name_parts) > 1 else name_parts[0]

    payload = {
        "data": {
            "type": "instant",
            "selected_offers": [offer_id],
            "payments": [
                {"type": "balance", "currency": total_currency, "amount": total_amount}
            ],
            "passengers": [
                {
                    "id": passenger_id,
                    "given_name": given_name,
                    "family_name": family_name,
                    "born_on": passenger_dob,
                    "email": "traveler@example.com",
                    "phone_number": "+6591234567",
                    "title": "mr",
                    "gender": "m",
                }
            ],
        }
    }

    try:
        async with httpx.AsyncClient(timeout=30.0) as client:
            response = await client.post(
                f"{config.DUFFEL_BASE_URL}/air/orders",
                headers=_duffel_headers(),
                json=payload,
            )
            if response.status_code >= 400:
                return {
                    "success": False,
                    "error": response.json().get("errors", [{"message": "Unknown booking error"}]),
                }
            data = response.json()
    except httpx.TimeoutException:
        return {
            "success": False,
            "error": [{"code": "timeout", "message": "Timed out creating order - booking status unknown, do not assume it failed"}],
        }

    return {
        "success": True,
        "bookingReference": data["data"].get("booking_reference"),
        "orderId": data["data"].get("id"),
        "totalAmount": data["data"].get("total_amount"),
    }

def _summarize_flight_failure(attempts: list) -> tuple[str, str]:
    """Backlog #9: translates technical attempt codes into a traveler-friendly
    reason and suggested next step."""
    codes = [c for a in attempts for c in a.get("codes", [])]
    if any(c in ("offer_no_longer_available", "offer_expired", "offer_request_already_booked") for c in codes):
        return (
            "The selected flight price is no longer available (it may have sold out or the price changed).",
            "Please search again for updated flight options and try booking a fresh result.",
        )
    if any(a.get("outcome") in ("timeout", "book_timeout_unsafe_to_retry") for a in attempts):
        return (
            "The booking request timed out and its status could not be confirmed.",
            "Please check with support before attempting to book again, to avoid a possible duplicate booking.",
        )
    return (
        "The flight booking could not be completed due to a validation error.",
        "Please double-check the passenger details and try again, or contact support.",
    )

async def book_flight_with_retry(offer_id: str, passenger_name: str, passenger_dob: str,
                                  origin: str = None, destination: str = None,
                                  date: str = None, max_retries: int = 2) -> dict:
    """Backlog #9: attempts to book a flight, with automatic recovery."""
    current_offer_id = offer_id
    attempts = []

    for attempt in range(max_retries + 1):
        try:
            result = await book_flight(current_offer_id, passenger_name, passenger_dob)
        except httpx.TimeoutException:
            attempts.append({"attempt": attempt + 1, "outcome": "timeout"})
            continue

        if result["success"]:
            result["attempts"] = attempts + [{"attempt": attempt + 1, "outcome": "success"}]
            return result

        error_codes = [e.get("code", "") for e in result.get("error", [])] if isinstance(result.get("error"), list) else []
        # Real Duffel error codes observed: offer_request_already_booked (offer
        # already consumed by a prior booking), offer_no_longer_available,
        # offer_expired (documented but not yet observed in testing)
        is_expired = any(code in (
            "offer_no_longer_available", "offer_expired", "offer_request_already_booked"
        ) for code in error_codes)
        attempts.append({"attempt": attempt + 1, "outcome": "failed", "codes": error_codes})

        if is_expired and origin and destination and date:
            fresh_flights = await search_flights(origin, destination, date)
            if fresh_flights:
                current_offer_id = fresh_flights[0]["offerId"]
                continue
            else:
                break
        elif not is_expired:
            break

    reason, next_step = _summarize_flight_failure(attempts)
    return {
        "success": False,
        "error": reason,
        "attempts": attempts,
        "requiresManualBooking": True,
        "nextSteps": next_step,
    }

async def cancel_flight_booking(order_id: str) -> dict:
    """Backlog #9: cancels a Duffel order. Two-step flow: first get a
    cancellation quote, then confirm it. Used for rollback when a linked
    booking (e.g. the hotel) fails after the flight already succeeded."""
    async with httpx.AsyncClient(timeout=20.0) as client:
        quote_response = await client.post(
            f"{config.DUFFEL_BASE_URL}/air/order_cancellations",
            headers=_duffel_headers(),
            json={"data": {"order_id": order_id}},
        )
        if quote_response.status_code >= 400:
            return {"success": False, "error": quote_response.text}
        quote_data = quote_response.json()["data"]
        cancellation_id = quote_data["id"]

        confirm_response = await client.post(
            f"{config.DUFFEL_BASE_URL}/air/order_cancellations/{cancellation_id}/actions/confirm",
            headers=_duffel_headers(),
        )
        if confirm_response.status_code >= 400:
            return {"success": False, "error": confirm_response.text,
                    "note": "Cancellation quote was created but not confirmed - order may still be active."}
        confirm_data = confirm_response.json()["data"]

    return {
        "success": True,
        "cancellationId": confirm_data.get("id"),
        "refundAmount": confirm_data.get("refund_amount"),
        "refundCurrency": confirm_data.get("refund_currency"),
        "confirmedAt": confirm_data.get("confirmed_at"),
    }