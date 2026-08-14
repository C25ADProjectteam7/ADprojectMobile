"""
Google Places API (New) client - restaurant and attraction search
Uses Text Search, which supports free-text queries (e.g. combining a category
with user preferences) plus location biasing via coordinates and radius.
Docs: https://developers.google.com/maps/documentation/places/web-service/text-search
"""
import httpx
import config
from agent.http_utils import retry_on_timeout

PLACES_API_BASE = "https://places.googleapis.com/v1/places:searchText"


@retry_on_timeout(max_attempts=3)
async def _search_places(query: str, latitude: float, longitude: float,
                          radius: int = 5000, max_results: int = 5) -> list[dict]:
    headers = {
        "Content-Type": "application/json",
        "X-Goog-Api-Key": config.GOOGLE_PLACES_API_KEY,
        "X-Goog-FieldMask": (
            "places.displayName,places.formattedAddress,places.rating,"
            "places.priceLevel,places.location,places.types"
        ),
    }
    payload = {
        "textQuery": query,
        "locationBias": {
            "circle": {
                "center": {"latitude": latitude, "longitude": longitude},
                "radius": radius,
            }
        },
        "maxResultCount": max_results,
    }

    async with httpx.AsyncClient(timeout=20.0) as client:
        response = await client.post(PLACES_API_BASE, headers=headers, json=payload)
        response.raise_for_status()
        data = response.json()

    results = []
    for place in data.get("places", []):
        # `or {}` (not `.get(key, {})`) - Places API results have been
        # observed with these keys present but explicitly null, and
        # dict.get's default only kicks in when the key is absent, not when
        # its value is None. Either way this would crash the whole batch,
        # not just skip this one place.
        location = place.get("location") or {}
        results.append({
            "name": (place.get("displayName") or {}).get("text", "Unknown"),
            "address": place.get("formattedAddress"),
            "rating": place.get("rating"),
            "priceLevel": place.get("priceLevel"),
            "latitude": location.get("latitude"),
            "longitude": location.get("longitude"),
        })
    return results


async def resolve_city_center(city_name: str) -> tuple[float, float] | None:
    """Resolves a city NAME to its center coordinates via Places Text Search
    (no location bias). Duffel's airport-derived coordinates place searches
    at the airport, which biases hotel results toward airport hotels
    (observed live: a Los Angeles search surfaced 'Hilton Los Angeles
    Airport'). Returns None when the lookup fails - callers fall back to
    airport coordinates."""
    headers = {
        "Content-Type": "application/json",
        "X-Goog-Api-Key": config.GOOGLE_PLACES_API_KEY,
        "X-Goog-FieldMask": "places.displayName,places.location",
    }
    try:
        async with httpx.AsyncClient(timeout=15.0) as client:
            response = await client.post(
                PLACES_API_BASE,
                headers=headers,
                json={"textQuery": city_name, "maxResultCount": 1},
            )
            response.raise_for_status()
            places = response.json().get("places") or []
    except Exception:
        return None
    for place in places:
        location = place.get("location") or {}
        latitude, longitude = location.get("latitude"), location.get("longitude")
        if latitude is not None and longitude is not None:
            return float(latitude), float(longitude)
    return None


async def search_restaurants(city: str, latitude: float, longitude: float,
                              cuisine: str = "") -> dict:
    """Backlog #6/#7: searches restaurants near a coordinate, optionally filtered
    by cuisine/preference. Falls back to an unfiltered search if the preference-
    specific search finds nothing."""
    if cuisine:
        query = f"{cuisine} restaurants in {city}"
        results = await _search_places(query, latitude, longitude)
        if results:
            return {"restaurants": results, "preferenceRelaxed": False, "note": None}
        # Fall back to a generic search if the specific cuisine yielded nothing
        fallback = await _search_places(f"restaurants in {city}", latitude, longitude)
        return {
            "restaurants": fallback,
            "preferenceRelaxed": True,
            "note": f"No '{cuisine}' restaurants found; showing general options in {city} instead.",
        }

    results = await _search_places(f"restaurants in {city}", latitude, longitude)
    return {"restaurants": results, "preferenceRelaxed": False, "note": None}


async def search_attractions(city: str, latitude: float, longitude: float,
                              category: str = "") -> dict:
    """Backlog #6/#7: searches tourist attractions near a coordinate, optionally
    filtered by category. Falls back to a general search if the category-specific
    search finds nothing."""
    if category:
        query = f"{category} attractions in {city}"
        results = await _search_places(query, latitude, longitude)
        if results:
            return {"attractions": results, "preferenceRelaxed": False, "note": None}
        fallback = await _search_places(f"tourist attractions in {city}", latitude, longitude)
        return {
            "attractions": fallback,
            "preferenceRelaxed": True,
            "note": f"No '{category}' attractions found; showing general options in {city} instead.",
        }

    results = await _search_places(f"tourist attractions in {city}", latitude, longitude)
    return {"attractions": results, "preferenceRelaxed": False, "note": None}
