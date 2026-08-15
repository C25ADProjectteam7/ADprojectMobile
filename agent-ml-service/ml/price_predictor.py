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

from pathlib import Path

import joblib
import pandas as pd

from ml.schemas import HotelPriceRequest, HotelPriceResponse

MODEL_ARTIFACT_PATH = Path(__file__).resolve().parent.parent / "models" / "hotel_price_baseline.joblib"

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


# Training features not derivable from HotelPriceRequest at all (no city/
# room-type/etc crosswalk exists — see docs/ml/hotel-price-baseline-results.md).
# Held at the training set's most common (mode) value rather than invented.
# This means predictions currently do NOT vary by city or room_type.
_DEFAULT_HOTEL = "City Hotel"
_DEFAULT_ROOM_CODE = "A"
_DEFAULT_MARKET_SEGMENT = "Online TA"
_DEFAULT_DEPOSIT_TYPE = "No Deposit"
_DEFAULT_CUSTOMER_TYPE = "Transient"

_BASELINE_LIMITATION_MESSAGE = (
    "BASELINE model (RandomForest trained on the Hotel Booking Demand dataset). "
    "Only lead time, stay length, guest count, and arrival month currently affect "
    "this prediction. city, room_type, and other inputs are accepted by the API "
    "but NOT used by this model — the training dataset has no city or star-rating "
    "data, and room_type has no verified mapping to the dataset's room codes. "
    "Do not treat this as reflecting real city or room-type price differences."
)


class HotelPricePredictor:
    """
    Baseline predictor — loads a trained scikit-learn Pipeline
    (preprocessing + RandomForestRegressor) from models/hotel_price_baseline.joblib
    and predicts ADR (Average Daily Rate) as a proxy for price per night.

    See docs/ml/hotel-price-baseline-results.md for training details,
    feature availability vs. this API's schema, and known limitations.
    """

    MODEL_STATUS = "baseline"
    MODEL_VERSION = "baseline-rf-v1"

    def __init__(self, model_path: Path = MODEL_ARTIFACT_PATH):
        if not model_path.exists():
            raise FileNotFoundError(
                f"Model artifact not found at {model_path}. "
                "Run agent-ml-service/training/train_baseline.py to generate it."
            )
        self._pipeline = joblib.load(model_path)

    def predict(self, request: HotelPriceRequest) -> HotelPriceResponse:
        nights = (request.check_out_date - request.check_in_date).days
        lead_time = (request.check_in_date - request.booking_date).days

        features = pd.DataFrame([{
            "lead_time": lead_time,
            "nights": nights,
            "number_of_guests": request.number_of_guests,
            "arrival_month_num": request.check_in_date.month,
            "hotel": _DEFAULT_HOTEL,
            "reserved_room_type": _DEFAULT_ROOM_CODE,
            "market_segment": _DEFAULT_MARKET_SEGMENT,
            "deposit_type": _DEFAULT_DEPOSIT_TYPE,
            "customer_type": _DEFAULT_CUSTOMER_TYPE,
        }])

        predicted_adr = float(self._pipeline.predict(features)[0])
        price_per_night = round(max(predicted_adr, 0.0), 2)
        total = round(price_per_night * nights, 2)

        return HotelPriceResponse(
            predicted_price_per_night=price_per_night,
            predicted_total_price=total,
            number_of_nights=nights,
            currency=request.currency,
            model_status=self.MODEL_STATUS,
            model_version=self.MODEL_VERSION,
            is_mock=False,
            message=_BASELINE_LIMITATION_MESSAGE,
        )
