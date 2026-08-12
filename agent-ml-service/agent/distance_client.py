"""
Distance Matrix client - travel time estimation for hotel convenience
flagging (Backlog #5). Uses Google's Distance Matrix API.
"""
import httpx
import config


async def get_travel_times_minutes(origin_lat: float, origin_lng: float,
                                    destinations: list[tuple[float, float]]) -> list[int | None]:
    """Backlog #5: batched version - queries travel time from one origin to
    multiple destinations in a single API call, instead of one call per
    destination. Returns a list of minutes (or None per-destination on
    failure), in the same order as the input destinations list."""
    if not destinations:
        return []

    dest_param = "|".join(f"{lat},{lng}" for lat, lng in destinations)
    async with httpx.AsyncClient(timeout=15.0) as client:
        try:
            response = await client.get(
                "https://maps.googleapis.com/maps/api/distancematrix/json",
                params={
                    "origins": f"{origin_lat},{origin_lng}",
                    "destinations": dest_param,
                    "key": config.GOOGLE_PLACES_API_KEY,
                },
            )
            if response.status_code >= 400:
                return [None] * len(destinations)
            data = response.json()
            elements = data["rows"][0]["elements"]
            return [
                e["duration"]["value"] // 60 if e.get("status") == "OK" else None
                for e in elements
            ]
        except (httpx.TimeoutException, KeyError, IndexError):
            return [None] * len(destinations)