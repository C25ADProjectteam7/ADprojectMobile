"""
LiteAPI (Nuitée) client - hotel search
Replaces mock hotel data. Amadeus Hotel API was decommissioned July 2026;
Duffel Stays requires sales approval (not available for self-service accounts).
LiteAPI offers genuine self-service sandbox access - see team discussion.
Docs: https://docs.liteapi.travel/reference
"""
import httpx
import config
from agent.http_utils import retry_on_timeout

def _liteapi_headers() -> dict:
    return {
        "X-API-Key": config.LITEAPI_API_KEY,
        "accept": "application/json",
    }


@retry_on_timeout(max_attempts=3)
async def _get_hotel_list(latitude: float, longitude: float, radius: int = 5000) -> list[dict]:
    """Retrieves hotels near a coordinate, with basic metadata (name, address,
    rating). Does not include pricing - see _get_hotel_rates for that."""
    async with httpx.AsyncClient(timeout=20.0) as client:
        response = await client.get(
            f"{config.LITEAPI_BASE_URL}/data/hotels",
            headers=_liteapi_headers(),
            params={
                "latitude": latitude,
                "longitude": longitude,
                "radius": radius,
                "limit": 20,
            },
        )
        response.raise_for_status()
        data = response.json()

    return data.get("data", [])


@retry_on_timeout(max_attempts=3)
async def _get_hotel_rates(hotel_ids: list[str], check_in: str, check_out: str,
                            guest_nationality: str = "SG") -> dict:
    """Retrieves live rates for a specific set of hotel IDs.
    Returns a dict keyed by hotelId -> {price, offerId} for the cheapest rate found."""
    if not hotel_ids:
        return {}

    payload = {
        "hotelIds": hotel_ids,
        "occupancies": [{"adults": 1}],
        "guestNationality": guest_nationality,
        "currency": "USD",
        "checkin": check_in,
        "checkout": check_out,
        "roomMapping": False,
    }

    async with httpx.AsyncClient(timeout=30.0) as client:
        response = await client.post(
            f"{config.LITEAPI_BASE_URL}/hotels/rates",
            headers={**_liteapi_headers(), "Content-Type": "application/json"},
            json=payload,
        )
        response.raise_for_status()
        data = response.json()

    rates_by_hotel = {}
    for entry in data.get("data", []):
        hotel_id = entry.get("hotelId")
        room_types = entry.get("roomTypes", [])
        if not room_types:
            continue
        # Pick the room type with the lowest offerRetailRate, keep its offerId too
        cheapest_room = min(
            (rt for rt in room_types if "offerRetailRate" in rt),
            key=lambda rt: rt["offerRetailRate"]["amount"],
            default=None,
        )
        if cheapest_room:
            rates_by_hotel[hotel_id] = {
                "price": cheapest_room["offerRetailRate"]["amount"],
                "offerId": cheapest_room["offerId"],
            }
    return rates_by_hotel


async def search_hotels_by_coordinates(latitude: float, longitude: float, check_in: str,
                                        check_out: str, budget: float,
                                        guest_nationality: str = "SG") -> dict:
    """Backlog #5/#7: real hotel search - combines hotel metadata with live pricing."""
    hotels = await _get_hotel_list(latitude, longitude)
    if not hotels:
        return {"hotels": [], "budgetRelaxed": False, "note": "No hotels found near this location."}

    hotel_ids = [h["id"] for h in hotels]
    rates = await _get_hotel_rates(hotel_ids, check_in, check_out, guest_nationality)

    def _build_results(max_price):
        results = []
        for hotel in hotels:
            rate_info = rates.get(hotel["id"])
            if rate_info is None or rate_info["price"] > max_price:
                continue
            results.append({
                "name": hotel.get("name"),
                "city": hotel.get("city"),
                "address": hotel.get("address"),
                "pricePerNight": rate_info["price"],
                "rating": hotel.get("rating"),
                "offerId": rate_info["offerId"],
                "latitude": hotel.get("latitude"),
                "longitude": hotel.get("longitude"),
            })
        return sorted(results, key=lambda h: h["pricePerNight"])[:5]

    results = _build_results(budget)
    if results:
        return {"hotels": results, "budgetRelaxed": False, "note": None}

    relaxed_results = _build_results(budget * 1.5)
    if relaxed_results:
        return {
            "hotels": relaxed_results,
            "budgetRelaxed": True,
            "note": f"No hotels found within the ${budget:.0f} budget; showing options up to ${budget * 1.5:.0f} instead.",
        }

    return {"hotels": [], "budgetRelaxed": False, "note": "No hotels found near this location, even with a relaxed budget."}


async def prebook_hotel(offer_id: str) -> dict:
    """Backlog #8: Step 3 of LiteAPI's booking flow - verifies the room/rate
    is still available at the given price, and returns a prebookId needed
    for the actual booking step."""
    payload = {
        "offerId": offer_id,
        "usePaymentSdk": False,
    }
    async with httpx.AsyncClient(timeout=20.0) as client:
        response = await client.post(
            f"{config.LITEAPI_BASE_URL}/rates/prebook",
            headers={**_liteapi_headers(), "Content-Type": "application/json"},
            json=payload,
        )
        if response.status_code >= 400:
            return {"success": False, "error": response.text}
        data = response.json()

    return {"success": True, "raw": data}


async def book_hotel(prebook_id: str, first_name: str, last_name: str, email: str,
                      guest_first_name: str, guest_last_name: str,
                      guest_email: str = None, client_reference: str = None) -> dict:
    """Backlog #8: Step 4 of LiteAPI's booking flow.
    client_reference is LiteAPI's idempotency/dedup field - passing the same
    value on a retried request is intended to let LiteAPI recognize and
    reject a duplicate. Defaults to the prebookId if the caller doesn't
    supply one, since prebookId is already unique per booking attempt."""
    payload = {
        "prebookId": prebook_id,
        "clientReference": client_reference or prebook_id,
        "holder": {"firstName": first_name, "lastName": last_name, "email": email},
        "guests": [
            {
                "occupancyNumber": 1,
                "firstName": guest_first_name,
                "lastName": guest_last_name,
                "email": guest_email or email,
            }
        ],
        "payment": {"method": "ACC_CREDIT_CARD"},
    }
    async with httpx.AsyncClient(timeout=30.0) as client:
        response = await client.post(
            f"{config.LITEAPI_BOOKING_BASE_URL if hasattr(config, 'LITEAPI_BOOKING_BASE_URL') else config.LITEAPI_BASE_URL}/rates/book",
            headers={**_liteapi_headers(), "Content-Type": "application/json"},
            json=payload,
        )
        if response.status_code >= 400:
            return {"success": False, "error": response.text}
        data = response.json()["data"]

    return {
        "success": True,
        "bookingId": data.get("bookingId"),
        "status": data.get("status"),
        "hotelConfirmationCode": data.get("hotelConfirmationCode"),
        "hotelName": data.get("hotelName"),
        "checkin": data.get("checkin"),
        "checkout": data.get("checkout"),
        "totalPrice": data.get("price"),
        "currency": data.get("currency"),
        "paymentStatus": data.get("paymentStatus"),
    }

def _summarize_hotel_failure(attempts: list) -> tuple[str, str]:
    """Backlog #9: translates technical attempt outcomes into a traveler-friendly
    reason and suggested next step."""
    outcomes = [a.get("outcome") for a in attempts]
    if "book_timeout_unsafe_to_retry" in outcomes:
        return (
            "The booking request timed out and its status could not be confirmed.",
            "Please check with support before attempting to book again, to avoid a possible duplicate booking.",
        )
    if "prebook_failed" in outcomes:
        return (
            "The selected room rate is no longer available (it may have sold out or the price changed).",
            "Please search again for updated hotel options and try booking a fresh result.",
        )
    return (
        "The hotel booking could not be completed due to a validation error.",
        "Please double-check the guest details and try again, or contact support.",
    )

async def book_hotel_with_retry(offer_id: str, first_name: str, last_name: str, email: str,
                                 guest_first_name: str, guest_last_name: str,
                                 guest_email: str = None,
                                 latitude: float = None, longitude: float = None,
                                 check_in: str = None, check_out: str = None,
                                 budget: float = None, guest_nationality: str = "SG",
                                 max_retries: int = 2, client_reference: str = None) -> dict:
    """Backlog #9: attempts to prebook+book a hotel, with automatic recovery.

    IMPORTANT: unlike flights, LiteAPI does NOT reject a duplicate book() call
    with the same prebookId - it silently creates a second, separate booking
    (confirmed via testing). This means a timeout during the book() step must
    NEVER be blindly retried, since the first call may have already succeeded
    server-side. Only the prebook() step (which is safe to repeat) is retried
    automatically; a timeout during book() is treated as a hard failure
    requiring manual verification.
    """
    current_offer_id = offer_id
    attempts = []

    for attempt in range(max_retries + 1):
        try:
            prebook_result = await prebook_hotel(current_offer_id)
        except httpx.TimeoutException:
            attempts.append({"attempt": attempt + 1, "outcome": "prebook_timeout"})
            continue

        if not prebook_result["success"]:
            attempts.append({"attempt": attempt + 1, "outcome": "prebook_failed",
                              "error": prebook_result.get("error")})
            if latitude is not None and longitude is not None and check_in and check_out and budget is not None:
                fresh_search = await search_hotels_by_coordinates(
                    latitude, longitude, check_in, check_out, budget, guest_nationality
                )
                if fresh_search["hotels"]:
                    current_offer_id = fresh_search["hotels"][0]["offerId"]
                    continue
            break

        prebook_id = prebook_result["raw"]["data"]["prebookId"]

        try:
            book_result = await book_hotel(
                prebook_id, first_name, last_name, email, guest_first_name, guest_last_name,
                guest_email, client_reference or prebook_id
            )
        except httpx.TimeoutException:
            return {
                "success": False,
                "error": "Booking request timed out - status is UNKNOWN, not confirmed as failed. "
                         "Do not retry automatically. Manual verification required before attempting again.",
                "requiresManualVerification": True,
                "attempts": attempts + [{"attempt": attempt + 1, "outcome": "book_timeout_unsafe_to_retry"}],
            }

        if book_result["success"]:
            book_result["attempts"] = attempts + [{"attempt": attempt + 1, "outcome": "success"}]
            return book_result

        attempts.append({"attempt": attempt + 1, "outcome": "book_failed",
                          "error": book_result.get("error")})
        break

    reason, next_step = _summarize_hotel_failure(attempts)
    return {
        "success": False,
        "error": reason,
        "attempts": attempts,
        "requiresManualBooking": True,
        "nextSteps": next_step,
    }

async def cancel_hotel_booking(booking_id: str) -> dict:
    """Backlog #9: cancels a confirmed LiteAPI booking. Cancellation policy
    determines refund amount automatically - status will be CANCELLED (full
    refund/no charge) or CANCELLED_WITH_CHARGES (fees apply per policy)."""
    async with httpx.AsyncClient(timeout=20.0) as client:
        response = await client.put(
            f"{config.LITEAPI_BOOKING_BASE_URL}/bookings/{booking_id}",
            headers=_liteapi_headers(),
        )
        if response.status_code >= 400:
            return {"success": False, "error": response.text}
        data = response.json()["data"]

    return {
        "success": True,
        "bookingId": data.get("bookingId"),
        "status": data.get("status"),  # "CANCELLED" or "CANCELLED_WITH_CHARGES"
        "cancellationFee": data.get("cancellation_fee"),
        "refundAmount": data.get("refund_amount"),
        "currency": data.get("currency"),
    }