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
import logging
import re
from datetime import date, datetime, timedelta, timezone
from typing import Any
from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator
import config
from agent.deepseek_client import chat_completion, chat_json, chat_with_tools
from agent import tools as agent_tools
from agent.duffel_client import resolve_city_to_iata, resolve_city_to_country_code
from agent.exchange_rate_client import get_usd_to_sgd_rate
from agent.http_utils import SearchApiError
from agent.task_manager import set_task_stage

logger = logging.getLogger(__name__)

REQUIRED_FIELDS = ["originCity", "destination", "startDate", "endDate", "budgetTotal"]

# How many times to ask the LLM to (re-)assemble the itinerary JSON before
# giving up. Assembly is a stochastic LLM call - it occasionally garbles or
# fabricates an offerId despite the prompt's explicit instruction to preserve
# it verbatim, which _validate_and_normalize_itinerary correctly rejects
# rather than let a bad offerId reach booking. Retrying the same assembly
# prompt against the same already-gathered data often succeeds on a second
# attempt, without silently accepting output we can't trust.
ASSEMBLY_MAX_ATTEMPTS = 2

# Same rationale as ASSEMBLY_MAX_ATTEMPTS: chat_json's "JSON mode" from
# DeepSeek is not an airtight guarantee of well-formed JSON (confirmed live -
# extract_trip_requirements has thrown json.JSONDecodeError from production
# traffic), so a bare json.loads() with no retry turns an occasional
# malformed response into a hard 500 for the whole extraction call.
EXTRACTION_MAX_ATTEMPTS = 2


def _parse_llm_json(raw: str):
    """Parses LLM output into JSON, tolerating the common wrappers the model
    adds even in json_object mode: markdown code fences (```json ... ```),
    a leading prose sentence, or trailing text after the object. Extracts the
    first balanced {...} block and parses that; raises ValueError if none."""
    if raw is None:
        raise ValueError("Empty LLM output")
    text = raw.strip()
    # Strip markdown code fences
    fence = re.match(r"^```(?:json)?\s*(.*?)\s*```$", text, re.DOTALL)
    if fence:
        text = fence.group(1).strip()
    # Find the outermost braces of the first JSON object
    start = text.find("{")
    if start < 0:
        raise ValueError(f"No JSON object in LLM output: {text[:120]!r}")
    depth = 0
    in_string = False
    escape = False
    for i in range(start, len(text)):
        ch = text[i]
        if in_string:
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == '"':
                in_string = False
        else:
            if ch == '"':
                in_string = True
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    return json.loads(text[start:i + 1])
    raise ValueError(f"Unbalanced JSON in LLM output: {text[:120]!r}")

MAX_TOOL_CALLS_PER_TURN = 5
MAX_TOOL_CALLS_PER_TASK = 8
MAX_CALLS_PER_TOOL_PER_TASK = 2


class _ToolArgs(BaseModel):
    model_config = ConfigDict(extra="forbid")


class SearchFlightsArgs(_ToolArgs):
    origin: str = Field(min_length=3, max_length=3)
    destination: str = Field(min_length=3, max_length=3)
    date: str

    @field_validator("origin", "destination", mode="before")
    @classmethod
    def validate_iata_code(cls, value: str) -> str:
        if not isinstance(value, str):
            raise ValueError("must be a 3-letter IATA code")
        value = value.strip().upper()
        if not value.isalpha() or len(value) != 3:
            raise ValueError("must be a 3-letter IATA code")
        return value

    @field_validator("date")
    @classmethod
    def validate_date(cls, value: str) -> str:
        date.fromisoformat(value)
        return value


class SearchHotelsArgs(_ToolArgs):
    city: str = Field(min_length=1, max_length=100)
    check_in: str
    check_out: str
    budget: float = Field(gt=0)

    @field_validator("city")
    @classmethod
    def normalize_city(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("must not be blank")
        return value

    @field_validator("check_in", "check_out")
    @classmethod
    def validate_hotel_date(cls, value: str) -> str:
        date.fromisoformat(value)
        return value


class _CitySearchArgs(_ToolArgs):
    city: str = Field(min_length=1, max_length=100)

    @field_validator("city")
    @classmethod
    def normalize_city(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("must not be blank")
        return value


class SearchRestaurantsArgs(_CitySearchArgs):
    cuisine: str = Field(default="", max_length=100)


class SearchAttractionsArgs(_CitySearchArgs):
    category: str = Field(default="", max_length=100)


TOOL_ARGUMENT_MODELS: dict[str, type[_ToolArgs]] = {
    "search_flights": SearchFlightsArgs,
    "search_hotels": SearchHotelsArgs,
    "search_restaurants": SearchRestaurantsArgs,
    "search_attractions": SearchAttractionsArgs,
}

class ItineraryDay(BaseModel):
    """Validated shape for one day in an Agent-produced itinerary."""
    model_config = ConfigDict(extra="allow")

    date: str
    flight: dict[str, Any] | None = None
    hotel: dict[str, Any] | None = None
    breakfast: dict[str, Any] | None = None
    attraction: dict[str, Any] | None = None
    lunch: dict[str, Any] | None = None
    dinner: dict[str, Any] | None = None


def _offer_ids_from_gathered_data(gathered: dict, tool_name: str, result_key: str) -> set[str]:
    offer_ids = set()
    for call in gathered.get(tool_name, []):
        results = call.get("results", {})
        entries = results.get(result_key, []) if isinstance(results, dict) else results
        if not isinstance(entries, list):
            continue
        for entry in entries:
            if isinstance(entry, dict) and isinstance(entry.get("offerId"), str):
                offer_ids.add(entry["offerId"])
    return offer_ids


def _offer_ids_from_existing_itinerary(itinerary: dict | None, field_name: str) -> set[str]:
    if not isinstance(itinerary, dict):
        return set()
    offer_ids = set()
    for key, day in itinerary.items():
        if not key.startswith("day") or not isinstance(day, dict):
            continue
        item = day.get(field_name)
        if isinstance(item, dict) and isinstance(item.get("offerId"), str):
            offer_ids.add(item["offerId"])
    return offer_ids

def _validate_and_normalize_itinerary(
        itinerary: dict,
        gathered: dict,
        expected_num_days: int,
        expected_start_date: date | None = None,
        existing_itinerary: dict | None = None,
) -> dict:
    """Reject malformed or fabricated LLM output before it reaches Java.

    Generation validates against the requested dates. Modification permits a
    date change, but still requires the same number of continuous daily entries.
    """
    if not isinstance(itinerary, dict):
        raise ValueError("Agent returned an itinerary that is not a JSON object")
    if expected_num_days <= 0:
        raise ValueError("Itinerary must contain at least one day")

    expected_day_keys = {f"day{day}" for day in range(1, expected_num_days + 1)}
    actual_day_keys = {key for key in itinerary if key.startswith("day")}
    if actual_day_keys != expected_day_keys:
        raise ValueError("Agent itinerary must contain exactly one sequential day entry per trip day")

    flight_offer_ids = _offer_ids_from_gathered_data(gathered, "search_flights", "flights")
    hotel_offer_ids = _offer_ids_from_gathered_data(gathered, "search_hotels", "hotels")
    flight_offer_ids.update(_offer_ids_from_existing_itinerary(existing_itinerary, "flight"))
    hotel_offer_ids.update(_offer_ids_from_existing_itinerary(existing_itinerary, "hotel"))
    first_day_date = None

    for day_number in range(1, expected_num_days + 1):
        key = f"day{day_number}"
        try:
            day = ItineraryDay.model_validate(itinerary[key]).model_dump()
            parsed_date = date.fromisoformat(day["date"])
        except (ValidationError, TypeError, ValueError) as exc:
            raise ValueError(f"Invalid {key} in Agent itinerary: {exc}") from exc

        if first_day_date is None:
            first_day_date = parsed_date
        expected_date = (expected_start_date or first_day_date).fromordinal(
            (expected_start_date or first_day_date).toordinal() + day_number - 1
        )
        if parsed_date != expected_date:
            raise ValueError(f"Invalid {key} date: expected {expected_date.isoformat()}")

        for field_name, allowed_offer_ids in (("flight", flight_offer_ids), ("hotel", hotel_offer_ids)):
            item = day[field_name]
            if item is None:
                continue
            offer_id = item.get("offerId")
            if not isinstance(offer_id, str) or offer_id not in allowed_offer_ids:
                raise ValueError(f"Invalid {field_name} offerId in {key}")

        itinerary[key] = day

    total_cost = itinerary.get("totalCost")
    if isinstance(total_cost, bool) or not isinstance(total_cost, (int, float)) or total_cost < 0:
        raise ValueError("Agent itinerary must contain a non-negative numeric totalCost")
    itinerary["totalCost"] = float(total_cost)

    warnings = itinerary.get("warnings", [])
    if not isinstance(warnings, list) or not all(isinstance(warning, str) for warning in warnings):
        raise ValueError("Agent itinerary warnings must be a list of strings")
    itinerary["warnings"] = warnings

    # Only modify_itinerary's assembly prompt asks for this field - generation
    # has nothing to compare against, so it's optional (and left absent)
    # there. For modification (existing_itinerary is not None), it's
    # required and must actually be caught here as a validation failure -
    # not just type-checked when present - so a dropped field triggers
    # _assemble_and_validate_itinerary's retry instead of silently reaching
    # Java, which would otherwise default a missing field to "something
    # changed" even when the itinerary's own warnings honestly say nothing did.
    if existing_itinerary is not None:
        if not isinstance(itinerary.get("changeApplied"), bool):
            raise ValueError("Agent itinerary changeApplied must be a boolean (required when modifying an itinerary)")
        # changeApplied=false without a warning explaining why leaves the
        # traveler with an unexplained non-change - the assembly prompt asks
        # for this note, but nothing before this line actually enforced the
        # LLM followed through, so a genuinely empty warnings list used to
        # slip past validation and reach Java, which then shows a generic
        # "no changes were needed" instead of the specific reason (e.g.
        # "already the most expensive option available"). Rejecting here
        # triggers _assemble_and_validate_itinerary's retry, which - same as
        # every other validation failure in this function - often succeeds
        # on a second attempt against the same already-gathered data.
        if itinerary["changeApplied"] is False and not warnings:
            raise ValueError(
                "Agent itinerary changeApplied is false but warnings is empty - "
                "a note explaining why nothing was changed is required"
            )
    elif "changeApplied" in itinerary and not isinstance(itinerary["changeApplied"], bool):
        raise ValueError("Agent itinerary changeApplied must be a boolean")

    _enforce_scheduling_rules(itinerary)
    return itinerary


def _enforce_scheduling_rules(itinerary: dict) -> None:
    """Deterministic post-checks so an unusable schedule can't reach the app
    even when the LLM misapplied the prompt's rules (observed live: hotel
    check-in and dinner on the outbound day while the traveler was still
    airborne; the same restaurant scheduled 3 times). Dropped/fixed items are
    annotated in warnings - never silently changed."""
    day_keys = sorted(
        (k for k in itinerary if k.startswith("day") and isinstance(itinerary[k], dict)),
        key=lambda k: int(k[3:]),
    )
    if not day_keys:
        return
    days = {k: itinerary[k] for k in day_keys}

    def parse_datetime(value):
        if not value:
            return None
        try:
            return datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        except ValueError:
            return None

    def activity_time(day: dict, item: dict):
        """Best-effort start time of a non-flight item on its day's date."""
        raw = item.get("startTime") or item.get("checkIn")
        if not raw:
            return None
        try:
            hhmm = datetime.strptime(str(raw), "%H:%M").time()
            return datetime.combine(date.fromisoformat(day["date"]), hhmm)
        except ValueError:
            return None

    warnings = itinerary.setdefault("warnings", [])
    outbound = days["day1"].get("flight")
    return_flight = days[day_keys[-1]].get("flight")

    # 1) Outbound day: nothing while airborne. Overnight flight -> the
    #    departure day holds ONLY the flight. Same-day flight -> nothing in
    #    the [departure, arrival + 1.5h] window; items after arrival + 1.5h
    #    are legitimately usable and stay.
    if isinstance(outbound, dict):
        dep = parse_datetime(outbound.get("departureTime"))
        arr = parse_datetime(outbound.get("arrivalTime"))
        overnight = bool(dep and arr and arr.date() > dep.date())
        for field in ("hotel", "breakfast", "attraction", "lunch", "dinner"):
            item = days["day1"].get(field)
            if not isinstance(item, dict):
                continue
            if overnight:
                days["day1"][field] = None
                warnings.append(
                    f"Removed {field} from day 1 - the outbound flight is overnight "
                    f"(lands the next day), so the departure day holds only the flight."
                )
            elif dep is not None:
                start = activity_time(days["day1"], item)
                usable_from = (arr + timedelta(minutes=90)) if arr is not None else None
                if start is not None and start >= dep and (usable_from is None or start < usable_from):
                    days["day1"][field] = None
                    warnings.append(
                        f"Removed {field} from day 1 - it falls while the outbound "
                        f"flight is airborne."
                    )

    # 2) Hotel: one stay covering EVERY night, including the arrival night.
    #    The LLM writes times as checkIn/checkOut while the app reads
    #    startTime/endTime - normalize the keys here so the app actually
    #    shows them. Fill the arrival-day check-in (>= arrival + 1.5h),
    #    strip times on middle days, and on the return day set check-out
    #    12:00 - or drop the entry when the return flight leaves before noon.
    arrival_key = None
    if isinstance(outbound, dict):
        arr = parse_datetime(outbound.get("arrivalTime"))
        if arr is not None:
            day1_date = date.fromisoformat(days["day1"]["date"])
            arrival_key = f"day{(arr.date() - day1_date).days + 1}"

    if arrival_key is not None:
        arrival_hotel = days.get(arrival_key, {}).get("hotel")
        if not isinstance(arrival_hotel, dict):
            # The LLM skipped the arrival night (observed live: a 19:52
            # landing produced a hotel only on days 2-3) - carry the stay's
            # hotel from a later day back onto the arrival day.
            for key in day_keys:
                candidate = days.get(key, {}).get("hotel")
                if isinstance(candidate, dict) and key != arrival_key:
                    arrival_hotel = dict(candidate)
                    days[arrival_key]["hotel"] = arrival_hotel
                    warnings.append(
                        f"Restored the hotel on {arrival_key} - the arrival night "
                        f"must be covered as part of the stay."
                    )
                    break
        if isinstance(arrival_hotel, dict) and isinstance(outbound, dict):
            arr = parse_datetime(outbound.get("arrivalTime"))
            earliest = arr + timedelta(minutes=90) if arr is not None else None
            checkin = activity_time(days[arrival_key], arrival_hotel)
            raw_checkin = arrival_hotel.get("startTime") or arrival_hotel.get("checkIn")
            if earliest is not None and (checkin is None or checkin < earliest):
                arrival_hotel["startTime"] = earliest.strftime("%H:%M")
                warnings.append(
                    f"Set hotel check-in on {arrival_key} to {earliest.strftime('%H:%M')} - "
                    f"the original time was "
                    f"{'missing' if checkin is None else 'before the flight landed'}."
                )
            elif raw_checkin is not None:
                # Key normalization only - the LLM used checkIn.
                arrival_hotel["startTime"] = str(raw_checkin)
            arrival_hotel.pop("checkIn", None)
            arrival_hotel.pop("checkOut", None)
            arrival_hotel.pop("endTime", None)

    last_key = day_keys[-1]
    for key in day_keys:
        if key == arrival_key or key == last_key:
            continue
        hotel = days[key].get("hotel")
        if isinstance(hotel, dict):
            hotel.pop("startTime", None)
            hotel.pop("checkIn", None)
            hotel.pop("endTime", None)
            hotel.pop("checkOut", None)

    if isinstance(return_flight, dict):
        return_dep = parse_datetime(return_flight.get("departureTime"))
        last_hotel = days[last_key].get("hotel")
        if isinstance(last_hotel, dict):
            if return_dep is not None and return_dep.hour < 12:
                days[last_key]["hotel"] = None
                warnings.append(
                    f"Removed hotel from the last day - the return flight departs at "
                    f"{return_dep.strftime('%H:%M')}, before the usual 12:00 check-out."
                )
            else:
                checkout = last_hotel.get("endTime") or last_hotel.get("checkOut")
                last_hotel["endTime"] = str(checkout) if checkout else "12:00"
                last_hotel.pop("startTime", None)
                last_hotel.pop("checkIn", None)
                last_hotel.pop("checkOut", None)

    # 3) Last day: nothing at/after the return flight departs.
    if isinstance(return_flight, dict):
        dep = parse_datetime(return_flight.get("departureTime"))
        if dep is not None:
            last_day = days[day_keys[-1]]
            for field in ("hotel", "breakfast", "attraction", "lunch", "dinner"):
                item = last_day.get(field)
                if not isinstance(item, dict):
                    continue
                start = activity_time(last_day, item)
                if start is not None and start >= dep:
                    last_day[field] = None
                    warnings.append(
                        f"Removed {field} from the last day - it starts after the "
                        f"return flight departs."
                    )

    # 4) Each restaurant/attraction at most once per trip (hotels legitimately
    #    repeat - it's one stay).
    seen: dict[str, set] = {"breakfast": set(), "lunch": set(), "dinner": set(), "attraction": set()}
    for key in day_keys:
        day = days[key]
        for field in ("breakfast", "lunch", "dinner", "attraction"):
            item = day.get(field)
            if not isinstance(item, dict):
                continue
            name = str(item.get("name") or "").strip()
            if not name:
                continue
            if name in seen[field]:
                day[field] = None
                warnings.append(
                    f"Removed duplicate {field} '{name}' on {key} - each restaurant/"
                    f"attraction appears at most once per trip."
                )
            else:
                seen[field].add(name)

async def _execute_tool_calls(
        tool_calls,
        tool_dispatch: dict[str, Any],
        call_counts: dict[str, int],
):
    """Validate and execute one LLM tool-call turn with bounded capabilities.

    A malformed or failed individual call becomes a tool result for the model to
    recover from; it must not fail the entire itinerary task. The caller keeps
    ``call_counts`` for the lifetime of a generation/modification task.
    """
    async def run_one(call, func_name: str, func_args: dict):
        try:
            args_model = TOOL_ARGUMENT_MODELS.get(func_name)
            if args_model is None:
                return call, func_name, func_args, {
                    "error": {"code": "unknown_tool", "message": f"Tool '{func_name}' is not available."}
                }
            validated_args = args_model.model_validate(func_args).model_dump()
        except (ValidationError, ValueError, TypeError) as exc:
            return call, func_name, func_args, {
                "error": {"code": "invalid_tool_arguments", "message": str(exc)}
            }

        func = tool_dispatch.get(func_name)
        if func is None:
            return call, func_name, validated_args, {
                "error": {"code": "tool_not_allowed", "message": f"Tool '{func_name}' is not allowed in this workflow."}
            }

        try:
            return call, func_name, validated_args, await func(**validated_args)
        except SearchApiError as exc:
            # Message is written by our own client code from the provider's
            # error body - safe and useful for the LLM to reason about (e.g.
            # "departure_date cannot be in the past").
            return call, func_name, validated_args, {
                "error": {"code": "tool_execution_failed", "message": str(exc)}
            }
        except Exception:
            return call, func_name, validated_args, {
                "error": {"code": "tool_execution_failed", "message": "The tool request failed. Try another valid query."}
            }

    scheduled = []
    immediate_results = []
    turn_counts: dict[str, int] = {}
    for index, call in enumerate(tool_calls):
        func_name = call.function.name
        try:
            func_args = json.loads(call.function.arguments)
            if not isinstance(func_args, dict):
                raise ValueError("Tool arguments must be a JSON object")
        except (json.JSONDecodeError, TypeError, ValueError) as exc:
            immediate_results.append((call, func_name, {}, {
                "error": {"code": "invalid_tool_arguments", "message": str(exc)}
            }))
            continue

        if index >= MAX_TOOL_CALLS_PER_TURN:
            immediate_results.append((call, func_name, func_args, {
                "error": {"code": "tool_turn_limit_exceeded", "message": "At most 5 tool calls are allowed per turn."}
            }))
            continue

        if sum(call_counts.values()) + sum(turn_counts.values()) >= MAX_TOOL_CALLS_PER_TASK:
            immediate_results.append((call, func_name, func_args, {
                "error": {"code": "tool_task_budget_exceeded", "message": "This task has reached its total tool call limit."}
            }))
            continue
        total_calls = call_counts.get(func_name, 0) + turn_counts.get(func_name, 0)
        if total_calls >= MAX_CALLS_PER_TOOL_PER_TASK:
            immediate_results.append((call, func_name, func_args, {
                "error": {"code": "tool_task_limit_exceeded", "message": "This tool has reached its task call limit."}
            }))
            continue

        turn_counts[func_name] = turn_counts.get(func_name, 0) + 1
        scheduled.append((call, func_name, func_args))

    for func_name, count in turn_counts.items():
        call_counts[func_name] = call_counts.get(func_name, 0) + count

    executed_results = await asyncio.gather(
        *[run_one(call, func_name, func_args) for call, func_name, func_args in scheduled]
    )
    return immediate_results + executed_results

def _build_extraction_prompt(conversation_summary: str | None = None) -> str:
    today = date.today()
    today_str = today.isoformat()
    weekday_name = today.strftime("%A")
    summary_section = (
        f"""
Summary of earlier parts of this conversation (older turns not shown verbatim
below - this summary is your only record of them, treat facts in it as
already established, same as if the user had just said them):
{conversation_summary}
"""
        if conversation_summary else ""
    )
    return f"""You are a business travel planning assistant. Extract structured trip
requirements from the user's free-text description.
{summary_section}

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

If earlier turns of this conversation are included above, treat this as an
ongoing exchange, not an isolated message: combine information already given
in earlier turns with whatever the latest message adds or changes, rather
than re-asking for something already answered. The latest user message is
still what you are directly responding to.

Return ONLY the JSON object, with no additional explanation or text.
"""


async def extract_trip_requirements(
        user_input: str,
        conversation_history: list[dict] | None = None,
        conversation_summary: str | None = None,
) -> dict:
    """Backlog #4: extract trip requirements from free text, generate a clarifying
    question when required fields are missing.

    conversation_history: prior turns of this trip's agent-chat conversation,
    oldest first, as [{"role": "user"|"assistant", "content": "..."}, ...].
    Passed as real prior messages (not folded into the system prompt) so the
    model sees the actual back-and-forth - lets a follow-up like "make it
    3000 instead" resolve against a destination/date mentioned two turns ago,
    instead of every call being evaluated in isolation. Trimmed to the most
    recent config.MAX_CONVERSATION_HISTORY turns to bound prompt size.

    conversation_summary: compressed summary of turns older than what
    conversation_history covers (see summarize_conversation()) - the caller
    is expected to have already dropped those older turns from
    conversation_history to bound prompt size, so this is the only remaining
    trace of anything established further back than the recent window."""
    messages = [{"role": "system", "content": _build_extraction_prompt(conversation_summary)}]
    for turn in (conversation_history or [])[-config.MAX_CONVERSATION_HISTORY:]:
        role = "assistant" if turn.get("role", "").lower() == "assistant" else "user"
        messages.append({"role": role, "content": turn.get("content", "")})
    messages.append({"role": "user", "content": user_input})

    last_error: Exception | None = None
    for attempt in range(1, EXTRACTION_MAX_ATTEMPTS + 1):
        raw = await chat_json(messages)
        try:
            return _parse_llm_json(raw)
        except ValueError as exc:
            last_error = exc
            logger.warning(
                "Extraction attempt %d/%d failed: %s",
                attempt, EXTRACTION_MAX_ATTEMPTS, exc,
            )
    raise last_error


def _build_summary_prompt(previous_summary: str | None, turns: list[dict]) -> str:
    history_text = "\n".join(
        f"{turn.get('role', 'user').upper()}: {turn.get('content', '')}" for turn in turns
    )
    prior_section = (
        f"Existing summary of even earlier turns (fold this in, don't drop it):\n{previous_summary}\n\n"
        if previous_summary else ""
    )
    return f"""You are compressing an older portion of a business-trip-planning
conversation into a short summary, so a future prompt can rely on this
summary instead of re-reading the full conversation.

{prior_section}Additional earlier turns to fold in (oldest first):
{history_text}

Write a single concise paragraph (2-4 sentences) capturing any concrete facts
mentioned - origin city, destination, dates, budget, preferences - and any
decisions made (e.g. "an itinerary was generated", "the traveler asked to
switch to a cheaper hotel"). Do not include filler, pleasantries, or your own
commentary. If an existing summary was given above, merge it with the new
turns into ONE updated summary - do not produce two separate summaries or
say "in addition to the previous summary".

Return ONLY the summary paragraph, no preamble, no quotes around it."""


async def summarize_conversation(previous_summary: str | None, turns: list[dict]) -> str:
    """Compresses conversation turns that have fallen outside the recent
    verbatim window (see extract_trip_requirements's conversation_history
    param) into a short running summary, so facts established many turns ago
    aren't simply lost once they age out of that window. Incremental: the
    caller passes the existing summary (if any) plus only the NEWLY-dropped
    turns since it was last computed, not the entire history every time."""
    messages = [
        {"role": "system", "content": _build_summary_prompt(previous_summary, turns)},
        {"role": "user", "content": "Summarize now."},
    ]
    summary = await chat_completion(messages, temperature=0.2)
    return summary.strip()


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
    this call must only use the data provided, never invent new options.
    Kept deliberately terse: every extra sentence here is input tokens that
    slow the LLM down without changing the output."""
    return f"""You are a business travel planner. Using ONLY the data below
(never invent flights/hotels/restaurants/attractions), assemble a {num_days}-day itinerary.

Trip: {trip_requirements['originCity']} -> {trip_requirements['destination']},
{trip_requirements['startDate']} to {trip_requirements['endDate']}, budget {trip_requirements['budgetTotal']}.

Data (JSON):
{json.dumps(gathered_data, indent=2)}

Return JSON with this exact structure:
{{
  "day1": {{"date": "YYYY-MM-DD", "flight": {{...}} or null, "hotel": {{...}} or null,
           "breakfast": {{...}} or null, "attraction": {{...}} or null,
           "lunch": {{...}} or null, "dinner": {{...}} or null}},
  "day2": {{ ... }}, ...,
  "totalCost": <number>,
  "warnings": ["..."]
}}

Scheduling rules:
- One "dayN" per day. Outbound flight on day1, return flight on the last day.
- Departure day (day1): if the outbound flight lands on a LATER day (overnight),
  day1 contains ONLY the flight - no hotel, no meals, no attractions. If it
  lands the same day: usable-from = arrival + 1.5h, no breakfast, lunch only
  if usable-from <= 13:30, dinner unless usable-from > 20:00, one attraction
  with a free 2h gap. NEVER schedule anything in the airborne window
  [departure, arrival + 1.5h] - the traveler is on the plane.
- Hotel: the SAME hotel covers EVERY night of the stay, INCLUDING the arrival
  night - even when the flight lands late in the evening, the hotel must still
  appear on the arrival day with check-in >=1.5h AFTER landing (e.g. 19:40
  arrival -> 21:30). Then on every following day without check-in/check-out
  times, and on the return day with check-out "endTime" 12:00. NEVER put the
  hotel on the outbound day, and never give it a check-in before the flight
  lands. Prefer centrally located hotels over airport hotels when both fit
  the budget.
- Every activity object MUST have "startTime" as "HH:MM". Never output null
  startTime.
- Meals/attractions use flight-time buffers:
  * Last day: nothing at/after the return flight's departure. must-leave-by =
    return departure - 3h. Breakfast only if >=09:00, lunch if >=14:00, dinner
    if >=20:00, attraction needs a free 2h gap before must-leave-by. If
    nothing fits, all null. Prefer a later return flight unless it's
    meaningfully pricier or adds a connection.
  * Middle days: breakfast, lunch, dinner, one attraction - no buffer math.
- Use each restaurant and each attraction AT MOST ONCE across the whole trip -
  vary choices across days and meals even if that means picking a
  lower-ranked option. (The hotel may repeat - it's one stay.)
- Vary restaurants across meals when options exist. Heavy dinner cuisine
  (seafood boil, hotpot, fine dining) is NOT breakfast - leave breakfast null
  over forcing a bad fit.
- Preserve flight/hotel source fields VERBATIM, especially "offerId" (required
  for booking - never drop/rename/omit it).
- No data for a category -> null. Relaxation flags ("budgetRelaxed"/
  "preferenceRelaxed") -> list their notes in "warnings". Empty flight search
  -> best-effort totalCost + warning, never invent a flight.
- totalCost = flight(s) + hotel(per-night x nights). Restaurants/attractions
  are informational, excluded from totalCost.
- Return ONLY the JSON object.
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
    """Backlog #8/#9 safety net: the LLM sometimes drops - or, for LiteAPI's
    ~1300-1400 character opaque hotel offerId blobs, subtly corrupts or
    truncates - the offerId field when assembling the itinerary JSON, even
    when explicitly instructed to preserve it verbatim. Confirmed via direct
    testing: the same request can come back with a single flipped character
    or a truncated string despite the hotel name being copied correctly, i.e.
    this isn't a rare edge case - long opaque tokens are just not something
    an LLM reliably reproduces character-for-character.

    Since offerId is required for booking, this ALWAYS overwrites it by
    looking up the matching flight/hotel (by flight number/hotel name) in the
    raw search data - trusting our own gathered data over whatever offerId
    string the LLM produced, rather than only patching it in when the field
    is missing outright (which would leave a corrupted-but-present value
    uncorrected)."""
    hotel_offers = {}
    for call in gathered.get("search_hotels", []):
        # A failed tool call stores {"error": {...}} instead of a result list -
        # iterating that dict yields its key strings ("error"), which then blow
        # up on .get(). Guard against both the failure shape and non-dict entries.
        results = call.get("results", {})
        entries = results.get("hotels", []) if isinstance(results, dict) else results
        if not isinstance(entries, list):
            continue
        for h in entries:
            if isinstance(h, dict) and isinstance(h.get("name"), str):
                hotel_offers[h["name"]] = h.get("offerId")

    flight_offers = {}
    for call in gathered.get("search_flights", []):
        results = call.get("results", [])
        if not isinstance(results, list):
            continue
        for f in results:
            if isinstance(f, dict) and isinstance(f.get("flightNumber"), str):
                flight_offers[f["flightNumber"]] = f.get("offerId")

    for key, day in itinerary.items():
        if not key.startswith("day") or not isinstance(day, dict):
            continue
        hotel = day.get("hotel")
        if hotel and hotel.get("name") in hotel_offers:
            hotel["offerId"] = hotel_offers[hotel["name"]]
        flight = day.get("flight")
        if flight and flight.get("flightNumber") in flight_offers:
            flight["offerId"] = flight_offers[flight["flightNumber"]]

async def _assemble_and_validate_itinerary(
        assembly_messages: list[dict],
        gathered: dict,
        expected_num_days: int,
        expected_start_date: date | None = None,
        existing_itinerary: dict | None = None,
) -> dict:
    """Calls the assembly LLM, repairs/validates its output, and retries the
    assembly call itself (not just a repair pass) up to ASSEMBLY_MAX_ATTEMPTS
    times if anything about the result is unusable - see ASSEMBLY_MAX_ATTEMPTS
    for why. This includes malformed JSON (json.loads failing) and a
    well-formed-but-wrong-shape response (e.g. a JSON array instead of an
    object), not just _validate_and_normalize_itinerary's own checks - all of
    it is "this attempt's output can't be used, try again", not "the whole
    task should crash" (json.JSONDecodeError is itself a ValueError
    subclass, but everything up to and including json.loads() used to sit
    OUTSIDE the try/except below, so it was never actually retried)."""
    last_error: Exception | None = None
    for attempt in range(1, ASSEMBLY_MAX_ATTEMPTS + 1):
        raw_itinerary = await chat_json(assembly_messages)
        try:
            itinerary = _parse_llm_json(raw_itinerary)
            _ensure_offer_ids(itinerary, gathered)
            return _validate_and_normalize_itinerary(
                itinerary, gathered, expected_num_days, expected_start_date, existing_itinerary
            )
        except (ValueError, TypeError, AttributeError) as exc:
            last_error = exc
            logger.warning(
                "Itinerary assembly attempt %d/%d failed: %s",
                attempt, ASSEMBLY_MAX_ATTEMPTS, exc,
            )
    raise last_error


async def generate_itinerary(trip_requirements: dict, debug: bool = False) -> dict:
    """Backlog #6: full itinerary generation.
    Phase 1: gather real flight/hotel/restaurant/attraction data via tool calls.
    Phase 2: assemble that data into a structured day-by-day itinerary JSON.
    debug: if True, includes the full rawSearchData in the response (useful
    for development/debugging); defaults to False to keep the production
    response payload lean."""
    origin_city_name = trip_requirements['originCity']
    set_task_stage("resolving_locations")
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

    # Duffel (and LiteAPI) only return offers for dates today-or-later; a past
    # departure_date gets HTTP 422 from the search itself, which the LLM then
    # turns into "no flights found" and the trip ends up silently empty. Fail
    # fast here instead with a message the traveler can actually act on.
    today = datetime.now(timezone.utc).date()
    if start < today:
        return {
            "error": (
                f"The trip start date {start} is in the past. Flight and hotel "
                f"searches only cover dates from today ({today}) onwards - please "
                f"update the trip dates and try again."
            ),
        }
    if end < start:
        return {
            "error": (
                f"The trip end date {end} is before the start date {start}. "
                f"Please check the dates and try again."
            ),
        }

    resolved_requirements = {**trip_requirements, "originCity": origin_code, "destination": dest_code}

    tool_dispatch = dict(agent_tools.SEARCH_TOOL_FUNCTIONS)
    tool_dispatch["search_hotels"] = functools.partial(
        agent_tools.search_hotels, guest_nationality=guest_nationality
    )

    # ---- Phase 1: gather raw data via tool calls ----
    set_task_stage("searching_flights_hotels")
    messages = [
        {"role": "system", "content": _build_gather_prompt(resolved_requirements, num_days)},
        {"role": "user", "content": "Please gather all the data needed for this trip."},
    ]
    schemas = agent_tools.get_search_tool_schemas()
    gathered = {"search_flights": [], "search_hotels": [], "search_restaurants": [], "search_attractions": []}
    tool_call_counts: dict[str, int] = {}

    # Speed: the gather prompt instructs the LLM to fire ALL 5 searches
    # concurrently in its first response (MAX_TOOL_CALLS_PER_TURN == 5), so
    # the ideal case is 1 tool turn + 1 plain-text confirmation. 2 turns is
    # enough headroom for the LLM splitting calls across turns; 4 just paid
    # for extra LLM round-trips that never produced new data.
    for _ in range(2):
        message = await chat_with_tools(messages, schemas)

        if not message.tool_calls:
            break

        messages.append(message.model_dump(exclude_none=True))
        tool_results = await _execute_tool_calls(message.tool_calls, tool_dispatch, tool_call_counts)
        for call, func_name, func_args, result in tool_results:
            if func_name in gathered:
                gathered[func_name].append({"args": func_args, "results": result})
            messages.append({
                "role": "tool",
                "tool_call_id": call.id,
                "content": json.dumps(result),
            })

    # ---- Phase 2: assemble the structured itinerary from gathered data ----
    set_task_stage("assembling_itinerary")
    trimmed_data = _trim_for_assembly(gathered)
    assembly_messages = [
        {"role": "system", "content": _build_assembly_prompt(resolved_requirements, num_days, trimmed_data)},
        {"role": "user", "content": "Assemble the itinerary now."},
    ]
    itinerary = await _assemble_and_validate_itinerary(assembly_messages, gathered, num_days, start)

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

    # Carried forward (not re-derived) on every subsequent modify_itinerary
    # call, since the traveler's original commute preference isn't part of
    # what modify_itinerary re-extracts - see modify_itinerary's own read of
    # this same field.
    itinerary["maxHotelCommuteMinutes"] = threshold

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

    # email is the traveler's real contact address (this function's own
    # required parameter) - book_flight_with_retry's email default only
    # exists for the separate, currently-unreachable LLM-tool-calling
    # booking path (see its docstring), not for this one.
    flight_result = await book_flight_with_retry(
        flight_offer_id, passenger_name, passenger_dob, origin, destination, date_str, email=email
    )
    if not flight_result["success"]:
        # Surface book_flight_with_retry's own specific reason/nextSteps at
        # the top level too - not just nested under flightResult - so a
        # caller that only reads the top-level message/nextSteps (the more
        # obvious place to look) still gets the accurate, specific guidance
        # (e.g. "price no longer available, search again" vs "timed out,
        # don't blindly retry - contact support") instead of a generic one
        # that loses the distinction _summarize_flight_failure computed.
        # Mirrors the hotel-failure branch below, which already does this.
        return {
            "success": False,
            "stage": "flight",
            "flightResult": flight_result,
            "hotelResult": None,
            "message": f"Flight booking failed: {flight_result.get('error', 'Unknown error.')} "
                       f"No hotel booking was attempted.",
            "nextSteps": flight_result.get(
                "nextSteps", "Please try booking again, or contact support if this persists."
            ),
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
- "a more expensive hotel" / "upgrade the hotel" -> call search_hotels with a
  higher budget (and/or better rating/amenities) so the results actually
  differ from the current hotel, not just repeat the cheapest option
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
current one (all "dayN" keys, "totalCost", "warnings"), plus one additional
top-level field: "changeApplied" (true/false). Only modify the specific
field(s) the traveler asked to change - leave every other field exactly as
it was in the current itinerary. Recalculate "totalCost" if the change
affects flight or hotel pricing. Add a note to "warnings" summarizing what
happened - either what was changed (e.g. "Hotel changed from X to Y per
your request") or, if the newly gathered data has nothing better than what
the traveler already has (e.g. they asked for a more expensive/cheaper
hotel but no other option exists in range), an honest note that nothing
better was found and the itinerary was left as-is. Set "changeApplied" to
true only if you actually replaced a flight/hotel/restaurant/attraction
with a genuinely different one - set it to false if you kept everything
identical because nothing better was available. Never fabricate a
different-looking option just to make "changeApplied" true.

The traveler's totalCost figure represents the authoritative USD amount and
must be recalculated and returned if the change affects flight/hotel pricing.
Do NOT include or attempt to calculate totalCostSGD, totalCostOriginalUSD,
or any exchange-rate fields - those are computed by the server after your
response is validated, not part of what you should return.

Use judgment about meal appropriateness when selecting/keeping restaurant
choices: heavy dinner-style cuisine is usually not suitable for breakfast.

Keep the itinerary usable - these constraints always apply, including to the
parts you leave unchanged:
- Never schedule anything at/after an outbound flight's departure on its day
  (an overnight outbound means the departure day holds ONLY the flight), and
  never schedule anything at/after the return flight's departure on the last
  day.
- Never put the hotel on the outbound flight day, and never give it a
  check-in before the outbound flight lands. The hotel may repeat across the
  nights of the stay.
- Each restaurant and each attraction appears at most once across the whole
  trip - never reuse one you're keeping from the current itinerary.

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
    # generate_itinerary's output no longer carries totalCost (replaced by
    # totalCostSGD/totalCostOriginalUSD), so the LLM never sees it unless we
    # backfill it here for the prompts only - current_itinerary itself stays untouched.
    prompt_itinerary = dict(current_itinerary)
    original_usd = prompt_itinerary.get("totalCostOriginalUSD")
    if (
        "totalCost" not in prompt_itinerary
        and isinstance(original_usd, (int, float))
        and not isinstance(original_usd, bool)
        and original_usd >= 0
    ):
        prompt_itinerary["totalCost"] = original_usd

    tool_dispatch = dict(agent_tools.SEARCH_TOOL_FUNCTIONS)
    tool_dispatch["search_hotels"] = functools.partial(
        agent_tools.search_hotels, guest_nationality=guest_nationality
    )

    messages = [
        {"role": "system", "content": _build_modify_prompt(prompt_itinerary, user_request)},
        {"role": "user", "content": user_request},
    ]
    schemas = agent_tools.get_search_tool_schemas()
    gathered = {"search_flights": [], "search_hotels": [], "search_restaurants": [], "search_attractions": []}
    tool_call_counts: dict[str, int] = {}

    for _ in range(3):
        message = await chat_with_tools(messages, schemas)

        if not message.tool_calls:
            break

        messages.append(message.model_dump(exclude_none=True))
        tool_results = await _execute_tool_calls(message.tool_calls, tool_dispatch, tool_call_counts)
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
        {"role": "system", "content": _build_modify_assembly_prompt(prompt_itinerary, user_request, trimmed_data)},
        {"role": "user", "content": "Apply the change now."},
    ]
    current_day_count = sum(
        1 for key in current_itinerary
        if key.startswith("day") and isinstance(current_itinerary[key], dict)
    )
    updated_itinerary = await _assemble_and_validate_itinerary(
        assembly_messages, gathered, current_day_count, existing_itinerary=current_itinerary
    )

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

    # Read from the ORIGINAL input, not the LLM-assembled output: the
    # traveler's commute preference isn't something modify_itinerary asks the
    # LLM to reproduce, so relying on the LLM to echo it back would be as
    # fragile as the totalCost-dropping issue this same pattern fixed
    # elsewhere. Falls back to 45 for itineraries generated before this field
    # existed.
    threshold = current_itinerary.get("maxHotelCommuteMinutes") or 45
    convenience_warning = await _evaluate_hotel_convenience(updated_itinerary, threshold_minutes=threshold)
    if convenience_warning:
        updated_itinerary.setdefault("warnings", []).append(convenience_warning)

    updated_itinerary["maxHotelCommuteMinutes"] = threshold
    updated_itinerary["generatedAt"] = datetime.now(timezone.utc).isoformat()

    return updated_itinerary
