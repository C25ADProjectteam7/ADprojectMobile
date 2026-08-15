"""
Isolated LiteAPI fair-price probe — ML comparison only
======================================================
Deliberately SEPARATE from the Agent's hotel-search / booking pricing flow.
That flow prices whole stays in USD for budget logic; this probe asks a
different question and must not perturb it:

    same hotel, user's real check-in, checkout = check-in + 1 day,
    1 room, 2 adults, currency INR

which reproduces the model's training context exactly
(PER_NIGHT_1ROOM_2ADULTS). No division by nights is performed, because
multi-night totals are not linear in nights.

LiteAPI prices are TOTALS for the requested stay; with a 1-night, 1-room
request the total IS the comparable one-night price.

This module performs no ML. It only fetches and shapes the comparison input.
"""
from __future__ import annotations

from datetime import datetime, timedelta
from typing import Optional

import httpx

import config

PROBE_CURRENCY = "INR"
PROBE_ADULTS = 2
PROBE_ROOMS = 1
PROBE_NIGHTS = 1
OFFER_SELECTION_RULE = "CHEAPEST_COMPARABLE_ONE_NIGHT"


def _headers() -> dict:
    return {"X-API-Key": config.LITEAPI_API_KEY, "accept": "application/json"}


def next_day(date_str: str) -> str:
    return (datetime.strptime(date_str, "%Y-%m-%d") + timedelta(days=PROBE_NIGHTS)).strftime("%Y-%m-%d")


async def fetch_hotel_country(hotel_id: str) -> Optional[str]:
    """`/data/hotels` carries `country`; the search flow discards it, but the
    India scope guard needs it, so the probe fetches it explicitly."""
    async with httpx.AsyncClient(timeout=20.0) as client:
        r = await client.get(f"{config.LITEAPI_BASE_URL}/data/hotels",
                             headers=_headers(), params={"hotelIds": hotel_id})
        r.raise_for_status()
        data = r.json().get("data") or []
    return (data[0].get("country") if data else None)


def select_comparable_offer(rates_payload: dict, hotel_id: str) -> Optional[dict]:
    """Deterministic rule: cheapest valid one-night offer for this hotel.

    The Agent's search result does not carry the traveller's specific
    room/rate context, so an exact rate match is not possible. The selection
    rule is therefore reported back to the caller rather than implied.
    """
    best = None
    for entry in rates_payload.get("data", []):
        if str(entry.get("hotelId")) != str(hotel_id):
            continue
        for rt in entry.get("roomTypes", []) or []:
            amount, currency = _amount_of(rt)
            if amount is None or amount <= 0:
                continue
            rates = rt.get("rates") or [{}]
            first = rates[0] if rates else {}
            cand = {
                "offerId": rt.get("offerId"),
                "roomName": rt.get("name") or first.get("name"),
                "boardType": rt.get("boardType") or first.get("boardType"),
                "boardName": rt.get("boardName") or first.get("boardName"),
                "refundableTag": (((first.get("cancellationPolicies") or {})
                                   .get("refundableTag"))
                                  or ((rt.get("cancellationPolicies") or {})
                                      .get("refundableTag"))),
                "adultCount": first.get("adultCount"),
                "childCount": first.get("childCount"),
                "amount": amount,
                "currency": currency,
            }
            if best is None or cand["amount"] < best["amount"]:
                best = cand
    return best


def _amount_of(room_type: dict):
    """offerRetailRate first, then rates[].retailRate.total[0]."""
    orr = room_type.get("offerRetailRate") or {}
    if orr.get("amount") is not None:
        return float(orr["amount"]), orr.get("currency")
    for r in room_type.get("rates") or []:
        tot = ((r.get("retailRate") or {}).get("total") or [])
        if tot and tot[0].get("amount") is not None:
            return float(tot[0]["amount"]), tot[0].get("currency")
    return None, None


async def get_fair_price_probe(hotel_id: str, hotel_name: str, check_in: str) -> dict:
    """One-night INR probe for a single hotel. Never touches the USD flow."""
    check_out = next_day(check_in)
    payload = {
        "hotelIds": [hotel_id],
        "occupancies": [{"adults": PROBE_ADULTS}],   # 1 object == 1 room
        "currency": PROBE_CURRENCY,
	"guestNationality": "IN",
        "checkin": check_in,
        "checkout": check_out,
        "roomMapping": False,
    }
    async with httpx.AsyncClient(timeout=30.0) as client:
        r = await client.post(f"{config.LITEAPI_BASE_URL}/hotels/rates",
                              headers={**_headers(), "Content-Type": "application/json"},
                              json=payload)
        r.raise_for_status()
        data = r.json()

    offer = select_comparable_offer(data, hotel_id)
    if offer is None:
        return {"available": False, "reason": "NO_COMPARABLE_RATE"}

    country = None
    try:
        country = await fetch_hotel_country(hotel_id)
    except Exception:
        pass

    return {
        "available": True,
        "hotelId": hotel_id,
        "hotelName": hotel_name,
        "country": country,
        "checkIn": check_in,
        "checkOut": check_out,
        "nights": PROBE_NIGHTS,
        "rooms": PROBE_ROOMS,
        "adults": PROBE_ADULTS,
        "currency": offer.get("currency") or PROBE_CURRENCY,
        "comparableOneNightPrice": offer["amount"],
        "comparisonOfferSelection": OFFER_SELECTION_RULE,
        **{k: offer.get(k) for k in ("offerId", "roomName", "boardType", "boardName",
                                     "refundableTag", "adultCount", "childCount")},
    }
