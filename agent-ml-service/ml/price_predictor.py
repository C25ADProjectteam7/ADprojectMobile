"""
Price predictor — hotel price prediction interface
===================================================
Current status: MOCK implementation.
No real dataset or trained model exists yet.

To replace with a real model:
  1. Train a model and save it: joblib.dump(model, "price_predictor.joblib")
  2. Implement RealHotelPricePredictor below using that .joblib file
  3. Change the import in ml/routes.py from MockHotelPricePredictor to RealHotelPricePredictor,
     and update the `_predictor` instantiation in routes.py to use it
  4. The request/response *field names* in schemas.py are not expected to change,
     but a real model may need to loosen the SUPPORTED_CURRENCIES restriction
     (currently USD-only because this mock has no conversion) or add
     preprocessing/metadata — treat the contract as a stable target, not a guarantee.

The predictor is intentionally separated from the route so that swapping the
implementation is a small, contained change rather than touching the API layer.
"""

from ml.schemas import HotelPriceRequest, HotelPriceResponse

# Base prices per city, in USD — deterministic lookup, not ML.
# schemas.py currently restricts requests to currency="USD" (mock has no FX
# conversion), so these values can be returned as-is without relabeling risk.
_CITY_BASE_PRICES: dict[str, float] = {
    "tokyo": 160.0,
    "bangkok": 70.0,
    "paris": 220.0,
    "london": 200.0,
    "new york": 250.0,
    "sydney": 180.0,
    "singapore": 140.0,
    "dubai": 190.0,
    "bali": 60.0,
    "barcelona": 150.0,
}

_DEFAULT_BASE_PRICE = 120.0

# Deterministic multipliers — same input always produces same output
_STAR_MULTIPLIERS: dict[int, float] = {1: 0.5, 2: 0.75, 3: 1.0, 4: 1.4, 5: 2.0}
_ROOM_MULTIPLIERS: dict[str, float] = {
    "single": 0.85,
    "double": 1.0,
    "twin": 1.05,
    "suite": 2.2,
}


class MockHotelPricePredictor:
    """
    MOCK predictor — returns deterministic rule-based estimates.
    Not a trained ML model. All outputs are illustrative only.
    """

    MODEL_VERSION = "mock-v0"
    MODEL_STATUS = "mock"

    def predict(self, request: HotelPriceRequest) -> HotelPriceResponse:
        base = _CITY_BASE_PRICES.get(request.city.lower(), _DEFAULT_BASE_PRICE)
        star_mult = _STAR_MULTIPLIERS[request.hotel_star_rating]
        room_mult = _ROOM_MULTIPLIERS[request.room_type]

        price_per_night = round(base * star_mult * room_mult, 2)
        nights = (request.check_out_date - request.check_in_date).days
        total = round(price_per_night * nights, 2)

        return HotelPriceResponse(
            predicted_price_per_night=price_per_night,
            predicted_total_price=total,
            number_of_nights=nights,
            currency=request.currency,
            model_status=self.MODEL_STATUS,
            model_version=self.MODEL_VERSION,
            is_mock=True,
            message=(
                "MOCK prediction only — based on fixed lookup tables, not a trained model. "
                "Do not use this result for real booking decisions."
            ),
        )
