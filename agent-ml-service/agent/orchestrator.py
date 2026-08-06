"""
Agent orchestrator - itinerary generation and modification logic
================================================================
generate_itinerary flow:
1. Receive TripRequest (destination, dates, budget, preferences)
2. Build system prompt (agent role + behavioral constraints)
3. Call ML budget allocator -> get spending breakdown per category
4. Guide agent step by step: search flights -> hotels -> restaurants -> assemble
5. Agent uses tool calling to fetch real data
6. Parse results into structured itinerary JSON
7. Return to Spring Boot backend

modify_itinerary flow:
1. Load conversation history from DB
2. User sends change request -> agent analyzes delta -> re-searches -> updates
"""
import json
import asyncio
import functools
from datetime import date, datetime, timezone
from agent.deepseek_client import chat_json, chat_with_tools
from agent import tools as agent_tools
from agent.duffel_client import resolve_city_to_iata, resolve_city_to_country_code
from agent.exchange_rate_client import get_usd_to_sgd_rate


REQUIRED_FIELDS = ["originCity", "destination", "startDate", "endDate", "budgetTotal"]

async def _execute_tool_calls(tool_calls, tool_dispatch):
    """Execute all tool calls from one LLM turn concurrently, since they're
    independent of each other (e.g. searching flights doesn't depend on
    hotel search results). Returns a list of (call, func_name, func_args, result)."""
    async def run_one(call):
        func_name = call.function.name
        func_args = json.loads(call.function.arguments)
        func = tool_dispatch.get(func_name)
        result = await func(**func_args) if func else {"error": f"unknown tool {func_name}"}
        return call, func_name, func_args, result

    return await asyncio.gather(*[run_one(call) for call in tool_calls])

def _build_extraction_prompt() -> str:
    today = date.today()
    today_str = today.isoformat()
    weekday_name = today.strftime("%A")
    return f"""You are a business travel planning assistant. Extract structured trip
requirements from the user's free-text description.

Today's date is {today_str}, which is a {weekday_name}. When the user mentions
relative time expressions such as "next Monday", "the 15th of next month", or
"in three days", you must calculate the exact absolute date based on today's
date and day of week. Do not guess - count carefully from today's actual
weekday. For example, if today is a Tuesday and the user says "next Monday",
that means 6 days from today, landing on the following Monday - not this
week's Monday (which has already passed) and not the Monday after next.

Extract the following fields and return them as JSON:
- originCity: the city the traveler is departing from, in ENGLISH regardless
  of what language the user wrote it in (e.g. if the user writes "新加坡",
  output "Singapore"; if they write "上海", output "Shanghai"). Use the
  standard English name for the city. null if not mentioned.
- destination: destination city, same English-name requirement as above.
  null if not mentioned.
- startDate: departure date, format YYYY-MM-DD (resolve relative expressions using
  today's date; null if it truly cannot be determined)
- endDate: return date, format YYYY-MM-DD (if the user only gives a duration like
  "3 days" without a start date, this stays null too; if startDate and a duration
  are both known, endDate = startDate + duration - 1 day)
- budgetTotal: total budget, numeric type, null if not mentioned
- preferences: array of strings describing preferences, empty array [] if none
- maxHotelCommuteMinutes: integer or null. If the user expressed any preference
  about how close the hotel should be to the places they'll visit (e.g. "within
  20 minutes", "close to downtown", "not too far", "half an hour or less"),
  convert it to a reasonable number of minutes. Use your judgment for vague
  phrases (e.g. "close to downtown" -> 20, "not too far" -> 30). If no such
  preference was expressed at all, use null.
- missingFields: array listing which of the required fields (originCity/destination/
  startDate/endDate/budgetTotal) are currently null
- clarifyingQuestion: string - if missingFields is non-empty, write one natural
  follow-up question asking for the missing information; null if all fields are complete

Return ONLY the JSON object, with no additional explanation or text.
"""


async def extract_trip_requirements(user_input: str) -> dict:
    """Backlog #4: extract trip requirements from free text, generate a clarifying
    question when required fields are missing"""
    messages = [
        {"role": "system", "content": _build_extraction_prompt()},
        {"role": "user", "content": user_input},
    ]
    raw = await chat_json(messages)
    result = json.loads(raw)  # DeepSeek JSON mode guarantees valid JSON
    return result


def _build_search_prompt(trip_requirements: dict) -> str:
    """System prompt for Backlog #5: guides the agent to search flights and hotels
    within the traveler's confirmed budget and dates"""
    return f"""You are a business travel planning assistant with access to flight and
hotel search tools. The traveler's confirmed requirements are:
- Origin city: {trip_requirements['originCity']}
- Destination: {trip_requirements['destination']}
- Start date: {trip_requirements['startDate']}
- End date: {trip_requirements['endDate']}
- Total budget: {trip_requirements['budgetTotal']}
- Preferences: {trip_requirements.get('preferences', [])}

Use the available tools to search for flights and hotels that fit within budget.
Call search_flights first, then search_hotels. After both searches complete,
summarize the results in plain text - do not fabricate data you did not receive
from a tool call.

Do NOT call book_flight or book_hotel at this stage - this is search only,
not booking.
"""


async def search_travel_options(trip_requirements: dict) -> dict:
    """Backlog #5: drives the tool-calling loop - search_flights then search_hotels."""
    origin_city_name = trip_requirements['originCity']  # keep original name before IATA conversion
    origin_code = await resolve_city_to_iata(origin_city_name)
    dest_code = await resolve_city_to_iata(trip_requirements['destination'])
    guest_nationality = await resolve_city_to_country_code(origin_city_name) or "SG"

    if not origin_code or not dest_code:
        return {
            "flights": [],
            "hotels": [],
            "summary": f"Could not resolve airport codes for "
                       f"'{trip_requirements['originCity']}' or "
                       f"'{trip_requirements['destination']}'. Please provide a more "
                       f"specific city name.",
        }

    resolved_requirements = {**trip_requirements, "originCity": origin_code, "destination": dest_code}

    # Build a per-request dispatch table: search_hotels gets guest_nationality
    # pre-bound, so the LLM never needs to know it exists.
    tool_dispatch = dict(agent_tools.TOOL_FUNCTIONS)
    tool_dispatch["search_hotels"] = functools.partial(
        agent_tools.search_hotels, guest_nationality=guest_nationality
    )

    messages = [
        {"role": "system", "content": _build_search_prompt(resolved_requirements)},
        {"role": "user", "content": "Please search for flight and hotel options for this trip."},
    ]
    schemas = agent_tools.get_tool_schemas()
    collected_results = {"search_flights": [], "search_hotels": []}

    for _ in range(5):
        message = await chat_with_tools(messages, schemas)

        if not message.tool_calls:
            return {
                "flights": collected_results["search_flights"],
                "hotels": collected_results["search_hotels"],
                "summary": message.content,
            }


        messages.append(message.model_dump(exclude_none=True))
        tool_results = await _execute_tool_calls(message.tool_calls, tool_dispatch)
        for call, func_name, func_args, result in tool_results:
            if func_name in collected_results:
                collected_results[func_name].append({
                    "args": func_args,
                    "results": result,
                })
            messages.append({
                "role": "tool",
                "tool_call_id": call.id,
                "content": json.dumps(result),
            })


    return {
        "flights": collected_results["search_flights"],
        "hotels": collected_results["search_hotels"],
        "summary": "Search completed (loop limit reached).",
    }


def _build_gather_prompt(trip_requirements: dict, num_days: int) -> str:
    """Phase 1 system prompt: instructs the agent to gather all raw data needed
    for a full itinerary via tool calls. Does not ask the agent to assemble the
    final itinerary yet - that happens in phase 2."""
    return f"""You are a business travel planning assistant with access to flight,
hotel, restaurant, and attraction search tools. The traveler's confirmed trip is:
- Origin city: {trip_requirements['originCity']}
- Destination: {trip_requirements['destination']}
- Start date: {trip_requirements['startDate']}
- End date: {trip_requirements['endDate']} ({num_days} days)
- Total budget: {trip_requirements['budgetTotal']}
- Preferences: {trip_requirements.get('preferences', [])}

Gather everything needed to plan this {num_days}-day trip:
1. Call search_flights once for the outbound leg and once for the return leg.
2. Call search_hotels once for the full stay.
3. Call search_restaurants at least once - if any preference relates to food
   (cuisine, dietary needs), pass it via the cuisine parameter.
4. Call search_attractions at least once - if any preference relates to
   interests (e.g. museums, nature), pass it via the category parameter.

IMPORTANT for speed: none of these calls depend on each other's results. Issue
ALL of them (outbound flight, return flight, hotel, restaurants, attractions -
5 calls total) together in your FIRST response, rather than calling one tool,
waiting, then deciding the next. Do not call any tool more than once for the
same purpose (e.g. don't call search_hotels twice).

Do NOT call book_flight or book_hotel at this stage - this is planning only,
not booking. Booking only happens later when the traveler explicitly confirms
a specific choice.

Once you have gathered enough data, stop calling tools and reply with a brief
plain-text confirmation that data collection is complete.
"""

def _trim_for_assembly(gathered: dict) -> dict:
    """Strips down gathered tool-call data before feeding it into the assembly
    prompt, keeping only what's needed to build the itinerary and reducing
    prompt size (and thus DeepSeek processing time) for phase 2."""
    trimmed = {}
    for tool_name, calls in gathered.items():
        trimmed[tool_name] = []
        for call in calls:
            results = call["results"]
            # Unwrap the {hotels/restaurants/attractions: [...], note: ...} shape
            # into just the list, dropping the args since assembly doesn't need them
            if isinstance(results, dict):
                for key in ("hotels", "restaurants", "attractions"):
                    if key in results:
                        trimmed[tool_name].append({
                            "results": results[key][:3],  # top 3 is enough for assembly
                            "note": results.get("note"),
                        })
                        break
            else:
                trimmed[tool_name].append({"results": results[:3] if isinstance(results, list) else results})
    return trimmed


def _build_assembly_prompt(trip_requirements: dict, num_days: int, gathered_data: dict) -> str:
    """Phase 2 system prompt: assembles the final structured itinerary JSON
    from data already gathered in phase 1. No further tool calls happen here -
    this call must only use the data provided, never invent new options."""
    return f"""You are a business travel planning assistant. Using ONLY the real
data provided below (do not invent flights, hotels, restaurants, or attractions
that are not in this data), assemble a complete {num_days}-day itinerary.

Trip requirements:
- Origin: {trip_requirements['originCity']}, Destination: {trip_requirements['destination']}
- Dates: {trip_requirements['startDate']} to {trip_requirements['endDate']}
- Total budget: {trip_requirements['budgetTotal']}

Available real data (JSON):
{json.dumps(gathered_data, indent=2)}

Return a JSON object with this exact structure:
{{
  "day1": {{
    "date": "YYYY-MM-DD",
    "flight": {{...}} or null,
    "hotel": {{...}} or null,
    "breakfast": {{...}} or null,
    "attraction": {{...}} or null,
    "lunch": {{...}} or null,
    "dinner": {{...}} or null
  }},
  "day2": {{ ... }},
  ...
  "totalCost": <number>,
  "warnings": ["..."]  // empty array if nothing to flag
}}

Rules:
- Include one "dayN" key for each of the {num_days} days, numbered sequentially.
- Put the outbound flight on day1, the return flight on the last day ({num_days}).
- Hotel applies to every day EXCEPT the departure day (the traveler checks out
  that morning).

Meal/attraction scheduling MUST be computed from actual flight times using the
buffer logic below - do not guess based on the departure/arrival time alone.

DEPARTURE DAY (the last day, {num_days}) - work BACKWARDS from the return flight:
1. Compute "must-leave-by time" = the return flight's departureTime MINUS 3 hours.
   This accounts for travel to the airport plus check-in/security for an
   international flight.
2. Only schedule an activity or meal if it can reasonably conclude before the
   must-leave-by time:
   - Breakfast (assume it runs until ~09:00) - include only if
     must-leave-by time is 09:00 or later.
   - Lunch (assume it runs until ~14:00) - include only if
     must-leave-by time is 14:00 or later.
   - Dinner (assume it runs until ~20:00) - include only if
     must-leave-by time is 20:00 or later (rare for a departure day).
   - An attraction needs roughly 2 hours - include only if there is a clear
     2+ hour gap before the must-leave-by time that isn't already used by a
     scheduled meal.
3. If NONE of the above fit, leave breakfast/lunch/dinner/attraction all null
   for the departure day - do not force something in that doesn't fit.
4. If multiple return flight options were provided, prefer one that departs
   later in the day (more usable time before leaving), but do not pick one
   that costs meaningfully more or adds a connection just to gain an hour or
   two - balance usable time against price/convenience.

ARRIVAL DAY (day1) - work FORWARDS from the outbound flight:
1. Compute "usable-from time" = the outbound flight's arrivalTime PLUS 1.5 hours.
   This accounts for deplaning, immigration/customs, baggage claim, and
   transport from the airport into the city.
2. Never schedule breakfast on day1 (the traveler was in transit that morning).
3. Include lunch only if usable-from time is 13:30 or earlier (i.e. there's
   still time before a typical lunch window closes).
4. Always include dinner on day1 (evening is available regardless of a
   reasonable arrival time), unless usable-from time is after 20:00.
5. Include one attraction only if there's a clear 2+ hour gap between
   usable-from time and the next scheduled meal.

MIDDLE DAYS (any day that is neither day1 nor the last day): schedule
breakfast, lunch, dinner, and one attraction normally - no buffer math needed.

Other rules:
- Pick specific restaurants/attractions from the provided data for meals and
  sightseeing - do not repeat the exact same restaurant for every meal if
  multiple options were provided.
- Use judgment about meal appropriateness: heavy dinner-style cuisine (e.g.
  seafood boils, hotpot, fine dining) is usually NOT suitable for breakfast.
  If none of the provided restaurant options seem appropriate for breakfast,
  set "breakfast" to null rather than forcing an ill-fitting choice - it's
  better to leave breakfast unplanned than to suggest something unrealistic.
- CRITICAL: when including a flight or hotel object in the itinerary, you
  MUST preserve ALL fields from the source data exactly as given, especially
  "offerId" - this field is required for booking and must never be dropped,
  renamed, or omitted, even though it looks like an internal/technical field.
- If a category (e.g. attractions) has no data available, set that field to
  null rather than inventing something.
- search_hotels/search_restaurants/search_attractions results may include a
  "budgetRelaxed" or "preferenceRelaxed" flag with a "note" explaining a
  fallback was used. If any such flag is true, add a top-level "warnings"
  array to your output listing each note in plain English, so the traveler
  understands why a result doesn't perfectly match their original ask.
- If search_flights returned an empty list for either leg, set "totalCost" as
  best as possible from what IS available and add a warning noting that no
  flights were found for that route/date - do not invent a flight.
- totalCost must be the sum of the flight price(s) + hotel price (per night x
  nights) - restaurant/attraction costs are informational only and excluded
  from totalCost since pricing for those wasn't reliably available.
- Return ONLY the JSON object, no other text.
"""


async def _evaluate_hotel_convenience(itinerary: dict, threshold_minutes: int = 45) -> str | None:
    from agent.distance_client import get_travel_times_minutes

    hotel = None
    destinations = []
    for key, day in itinerary.items():
        if not key.startswith("day") or not isinstance(day, dict):
            continue
        if hotel is None and day.get("hotel"):
            hotel = day["hotel"]
        for field in ("attraction", "lunch", "dinner", "breakfast"):
            place = day.get(field)
            if place and place.get("latitude") is not None:
                destinations.append(place)

    if not hotel or hotel.get("latitude") is None or not destinations:
        return None

    coords = [(d["latitude"], d["longitude"]) for d in destinations]
    travel_times = await get_travel_times_minutes(hotel["latitude"], hotel["longitude"], coords)
    valid_times = [t for t in travel_times if t is not None]

    if not valid_times:
        return None

    avg_time = sum(valid_times) / len(valid_times)
    if avg_time > threshold_minutes:
        return (
            f"Your hotel ({hotel['name']}) is on average {avg_time:.0f} minutes "
            f"from the restaurants/attractions in this itinerary - you may want "
            f"to consider a more centrally located option."
        )
    return None


def _ensure_offer_ids(itinerary: dict, gathered: dict) -> None:
    """Backlog #8/#9 safety net: the LLM sometimes drops the offerId field
    when assembling the itinerary JSON, even when explicitly instructed to
    preserve it. Since offerId is required for booking, this repairs any
    missing offerId by looking up the matching flight/hotel (by name/flight
    number) in the raw search data, rather than relying solely on prompt
    compliance."""
    hotel_offers = {}
    for call in gathered.get("search_hotels", []):
        for h in call["results"].get("hotels", []):
            hotel_offers[h["name"]] = h.get("offerId")

    flight_offers = {}
    for call in gathered.get("search_flights", []):
        for f in call["results"]:
            flight_offers[f["flightNumber"]] = f.get("offerId")

    for key, day in itinerary.items():
        if not key.startswith("day") or not isinstance(day, dict):
            continue
        hotel = day.get("hotel")
        if hotel and not hotel.get("offerId") and hotel.get("name") in hotel_offers:
            hotel["offerId"] = hotel_offers[hotel["name"]]
        flight = day.get("flight")
        if flight and not flight.get("offerId") and flight.get("flightNumber") in flight_offers:
            flight["offerId"] = flight_offers[flight["flightNumber"]]

async def generate_itinerary(trip_requirements: dict, debug: bool = False) -> dict:
    """Backlog #6: full itinerary generation.
    Phase 1: gather real flight/hotel/restaurant/attraction data via tool calls.
    Phase 2: assemble that data into a structured day-by-day itinerary JSON.
    debug: if True, includes the full rawSearchData in the response (useful
    for development/debugging); defaults to False to keep the production
    response payload lean."""
    origin_city_name = trip_requirements['originCity']
    origin_code = await resolve_city_to_iata(origin_city_name)
    dest_code = await resolve_city_to_iata(trip_requirements['destination'])
    guest_nationality = await resolve_city_to_country_code(origin_city_name) or "SG"

    if not origin_code or not dest_code:
        return {
            "error": f"Could not resolve airport codes for "
                     f"'{trip_requirements['originCity']}' or "
                     f"'{trip_requirements['destination']}'. Please provide a more "
                     f"specific city name.",
        }

    start = date.fromisoformat(trip_requirements['startDate'])
    end = date.fromisoformat(trip_requirements['endDate'])
    num_days = (end - start).days + 1

    resolved_requirements = {**trip_requirements, "originCity": origin_code, "destination": dest_code}

    tool_dispatch = dict(agent_tools.TOOL_FUNCTIONS)
    tool_dispatch["search_hotels"] = functools.partial(
        agent_tools.search_hotels, guest_nationality=guest_nationality
    )

    # ---- Phase 1: gather raw data via tool calls ----
    messages = [
        {"role": "system", "content": _build_gather_prompt(resolved_requirements, num_days)},
        {"role": "user", "content": "Please gather all the data needed for this trip."},
    ]
    schemas = agent_tools.get_tool_schemas()
    gathered = {"search_flights": [], "search_hotels": [], "search_restaurants": [], "search_attractions": []}

    for _ in range(4):  # more tool calls expected than the simpler search_travel_options flow
        message = await chat_with_tools(messages, schemas)

        if not message.tool_calls:
            break

        messages.append(message.model_dump(exclude_none=True))
        tool_results = await _execute_tool_calls(message.tool_calls, tool_dispatch)
        for call, func_name, func_args, result in tool_results:
            if func_name in gathered:
                gathered[func_name].append({"args": func_args, "results": result})
            messages.append({
                "role": "tool",
                "tool_call_id": call.id,
                "content": json.dumps(result),
            })

    # ---- Phase 2: assemble the structured itinerary from gathered data ----
    trimmed_data = _trim_for_assembly(gathered)
    assembly_messages = [
        {"role": "system", "content": _build_assembly_prompt(resolved_requirements, num_days, trimmed_data)},
        {"role": "user", "content": "Assemble the itinerary now."},
    ]
    raw_itinerary = await chat_json(assembly_messages)
    itinerary = json.loads(raw_itinerary)
    _ensure_offer_ids(itinerary, gathered)

    if debug:
        itinerary["rawSearchData"] = gathered

    rate_info = await get_usd_to_sgd_rate()
    usd_to_sgd = rate_info["rate"]
    original_usd = itinerary.get("totalCost")
    if original_usd is not None:
        itinerary["totalCostSGD"] = round(original_usd * usd_to_sgd, 2)
        itinerary["currency"] = "SGD"
        itinerary["totalCostOriginalUSD"] = original_usd
        itinerary["exchangeRateUsed"] = usd_to_sgd

        if rate_info["source"] == "live":
            note = ("Amount shown is an estimate in SGD based on today's reference rate. "
                    "All bookings are actually transacted in USD.")
        elif rate_info["source"] == "cached":
            note = ("Amount shown is an estimate in SGD based on a recently cached "
                    "exchange rate (today's live rate was temporarily unavailable). "
                    "All bookings are actually transacted in USD.")
        else:
            note = ("Amount shown is a rough SGD estimate using a fallback exchange "
                    "rate (live rate data was unavailable). All bookings are "
                    "actually transacted in USD - please verify the exact amount "
                    "before relying on this figure.")
        itinerary["exchangeRateNote"] = note

        del itinerary["totalCost"]

    threshold = trip_requirements.get("maxHotelCommuteMinutes") or 45
    convenience_warning = await _evaluate_hotel_convenience(itinerary, threshold_minutes=threshold)
    if convenience_warning:
        itinerary.setdefault("warnings", []).append(convenience_warning)

    itinerary["generatedAt"] = datetime.now(timezone.utc).isoformat()

    return itinerary


def _is_itinerary_stale(itinerary: dict, max_age_minutes: int = 5) -> bool:
    """Backlog #9: checks whether an itinerary's embedded offerIds are likely
    stale (Duffel/LiteAPI offers typically expire within minutes to tens of
    minutes). Returns True if the itinerary is old enough that a fresh
    re-search is recommended before attempting to book from it."""
    generated_at_str = itinerary.get("generatedAt")
    if not generated_at_str:
        return True  # no timestamp - can't verify freshness, be conservative

    try:
        generated_at = datetime.fromisoformat(generated_at_str)
        age = datetime.now(timezone.utc) - generated_at
        return age.total_seconds() > max_age_minutes * 60
    except (ValueError, TypeError):
        return True  # malformed timestamp - be conservative

async def book_full_trip(itinerary: dict, flight_offer_id: str, hotel_offer_id: str,
                          passenger_name: str, passenger_dob: str,
                          email: str, origin: str = None, destination: str = None,
                          date_str: str = None, latitude: float = None, longitude: float = None,
                          check_in: str = None, check_out: str = None,
                          budget: float = None, guest_nationality: str = "SG") -> dict:
    """Backlog #9: books flight + hotel together. Proactively re-searches for
    fresh offers if the source itinerary looks stale, rather than waiting for
    a booking failure to trigger the reactive retry-with-research fallback."""
    from agent.duffel_client import book_flight_with_retry, cancel_flight_booking, search_flights
    from agent.liteapi_client import book_hotel_with_retry

    if _is_itinerary_stale(itinerary):
        # Proactively refresh both offers before attempting to book, rather
        # than relying solely on the reactive expired-offer retry path.
        if origin and destination and date_str:
            fresh_flights = await search_flights(origin, destination, date_str)
            if fresh_flights:
                flight_offer_id = fresh_flights[0]["offerId"]
        if latitude is not None and longitude is not None and check_in and check_out and budget is not None:
            from agent.liteapi_client import search_hotels_by_coordinates
            fresh_hotels = await search_hotels_by_coordinates(
                latitude, longitude, check_in, check_out, budget, guest_nationality
            )
            if fresh_hotels["hotels"]:
                hotel_offer_id = fresh_hotels["hotels"][0]["offerId"]

    flight_result = await book_flight_with_retry(
        flight_offer_id, passenger_name, passenger_dob, origin, destination, date_str
    )
    if not flight_result["success"]:
        return {
            "success": False,
            "stage": "flight",
            "flightResult": flight_result,
            "hotelResult": None,
            "message": "Flight booking failed. No hotel booking was attempted.",
            "nextSteps": "Please try booking again, or contact support if this persists.",
        }

    name_parts = passenger_name.strip().split(" ", 1)
    first_name = name_parts[0]
    last_name = name_parts[1] if len(name_parts) > 1 else name_parts[0]

    hotel_result = await book_hotel_with_retry(
        hotel_offer_id, first_name, last_name, email, first_name, last_name, email,
        latitude, longitude, check_in, check_out, budget, guest_nationality
    )
    if hotel_result["success"]:
        return {
            "success": True,
            "flightResult": flight_result,
            "hotelResult": hotel_result,
            "message": "Flight and hotel both booked successfully.",
        }

    order_id = flight_result.get("orderId")
    rollback_result = await cancel_flight_booking(order_id) if order_id else {"success": False, "error": "No orderId to cancel"}

    return {
        "success": False,
        "stage": "hotel",
        "flightResult": flight_result,
        "hotelResult": hotel_result,
        "rollback": rollback_result,
        "message": "Hotel booking failed after the flight was booked. "
                    + ("The flight booking has been automatically cancelled."
                       if rollback_result.get("success")
                       else "IMPORTANT: automatic cancellation of the flight FAILED - manual intervention required."),
        "nextSteps": "Please try booking again, or contact support to confirm your flight status."
                     if rollback_result.get("success")
                     else "URGENT: contact support immediately - your flight may still be booked without a hotel.",
    }

def _build_modify_prompt(current_itinerary: dict, user_request: str) -> str:
    return f"""You are a business travel planning assistant helping a traveler
modify an already-generated itinerary. You have access to the same flight,
hotel, restaurant, and attraction search tools used to build the original plan.

The traveler's CURRENT itinerary (JSON):
{json.dumps(current_itinerary, indent=2)}

The traveler's modification request:
"{user_request}"

Determine what needs to change and call the appropriate search tool(s) to
gather new options. For example:
- "a cheaper hotel" -> call search_hotels with a lower budget
- "different restaurant for dinner" -> call search_restaurants, possibly with
  a different cuisine if mentioned
- "an earlier flight" -> call search_flights again for the same route/date

Only call tools relevant to what the traveler asked to change - do not
re-search everything. Do NOT call book_flight or book_hotel at this stage -
modifying a plan is never a reason to book anything.

Once you have gathered what's needed, reply with a brief plain-text summary
of what you found (not the final itinerary yet).
"""


def _build_modify_assembly_prompt(current_itinerary: dict, user_request: str,
                                   new_data: dict) -> str:
    """Phase 2 system prompt for Backlog #10: applies the requested change to
    the itinerary using the newly gathered data, leaving everything else
    unchanged."""
    return f"""You are a business travel planning assistant. Apply the traveler's
requested change to their itinerary, using ONLY the newly gathered real data
below - do not invent anything.

CURRENT itinerary (JSON):
{json.dumps(current_itinerary, indent=2)}

Traveler's request: "{user_request}"

Newly gathered data to use for the change:
{json.dumps(new_data, indent=2)}

Return the COMPLETE updated itinerary in the same JSON structure as the
current one (all "dayN" keys, "totalCost", "warnings"). Only modify the
specific field(s) the traveler asked to change - leave every other field
exactly as it was in the current itinerary. Recalculate "totalCost" if the
change affects flight or hotel pricing. Add a note to "warnings" summarizing
what was changed (e.g. "Hotel changed from X to Y per your request").

Use judgment about meal appropriateness when selecting/keeping restaurant
choices: heavy dinner-style cuisine is usually not suitable for breakfast.

CRITICAL: when including a flight or hotel object (whether unchanged or newly
selected), you MUST preserve ALL fields exactly as given in the source data,
especially "offerId" - this field is required for booking and must never be
dropped, renamed, or omitted, even though it looks like an internal/technical
field.

Return ONLY the JSON object, no other text.
"""


async def modify_itinerary(current_itinerary: dict, user_request: str,
                            guest_nationality: str = "SG", debug: bool = False) -> dict:
    """Backlog #10: modifies an existing itinerary based on a natural-language
    change request. Stateless - the caller (Java backend) supplies the current
    itinerary each time; this function does not persist conversation history."""
    tool_dispatch = dict(agent_tools.TOOL_FUNCTIONS)
    tool_dispatch["search_hotels"] = functools.partial(
        agent_tools.search_hotels, guest_nationality=guest_nationality
    )

    messages = [
        {"role": "system", "content": _build_modify_prompt(current_itinerary, user_request)},
        {"role": "user", "content": user_request},
    ]
    schemas = agent_tools.get_tool_schemas()
    gathered = {"search_flights": [], "search_hotels": [], "search_restaurants": [], "search_attractions": []}

    for _ in range(3):
        message = await chat_with_tools(messages, schemas)

        if not message.tool_calls:
            break

        messages.append(message.model_dump(exclude_none=True))
        tool_results = await _execute_tool_calls(message.tool_calls, tool_dispatch)
        for call, func_name, func_args, result in tool_results:
            if func_name in gathered:
                gathered[func_name].append({"args": func_args, "results": result})
            messages.append({
                "role": "tool",
                "tool_call_id": call.id,
                "content": json.dumps(result),
            })

    trimmed_data = _trim_for_assembly(gathered)
    assembly_messages = [
        {"role": "system", "content": _build_modify_assembly_prompt(current_itinerary, user_request, trimmed_data)},
        {"role": "user", "content": "Apply the change now."},
    ]
    raw_itinerary = await chat_json(assembly_messages)
    updated_itinerary = json.loads(raw_itinerary)
    _ensure_offer_ids(updated_itinerary, gathered)

    if debug:
        updated_itinerary["rawSearchData"] = gathered

    rate_info = await get_usd_to_sgd_rate()
    usd_to_sgd = rate_info["rate"]
    original_usd = updated_itinerary.get("totalCost")
    if original_usd is not None:
        updated_itinerary["totalCostSGD"] = round(original_usd * usd_to_sgd, 2)
        updated_itinerary["currency"] = "SGD"
        updated_itinerary["totalCostOriginalUSD"] = original_usd
        updated_itinerary["exchangeRateUsed"] = usd_to_sgd

        if rate_info["source"] == "live":
            note = ("Amount shown is an estimate in SGD based on today's reference rate. "
                    "All bookings are actually transacted in USD.")
        elif rate_info["source"] == "cached":
            note = ("Amount shown is an estimate in SGD based on a recently cached "
                    "exchange rate (today's live rate was temporarily unavailable). "
                    "All bookings are actually transacted in USD.")
        else:
            note = ("Amount shown is a rough SGD estimate using a fallback exchange "
                    "rate (live rate data was unavailable). All bookings are "
                    "actually transacted in USD - please verify the exact amount "
                    "before relying on this figure.")
        updated_itinerary["exchangeRateNote"] = note

        del updated_itinerary["totalCost"]

    convenience_warning = await _evaluate_hotel_convenience(updated_itinerary)
    if convenience_warning:
        updated_itinerary.setdefault("warnings", []).append(convenience_warning)

    updated_itinerary["generatedAt"] = datetime.now(timezone.utc).isoformat()

    return updated_itinerary
