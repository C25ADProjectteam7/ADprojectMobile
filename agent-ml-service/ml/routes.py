"""ML API routes — FastAPI router for price prediction and budget allocation"""

from fastapi import APIRouter
from ml.schemas import HotelPriceRequest, HotelPriceResponse
from ml.price_predictor import HotelPricePredictor

router = APIRouter(prefix="/api/ml", tags=["Machine Learning"])

# MockHotelPricePredictor remains in ml/price_predictor.py for tests/fallback
# reference, but the live route now serves the trained baseline artifact.
_predictor = HotelPricePredictor()


@router.post("/predict-hotel-price", response_model=HotelPriceResponse)
def predict_hotel_price(request: HotelPriceRequest) -> HotelPriceResponse:
    """
    Predict hotel price per night and total stay cost.

    **Current status:** baseline model (RandomForest trained on the Hotel
    Booking Demand dataset) — see docs/ml/hotel-price-baseline-results.md.
    city and room_type are accepted but not yet used by the model; see the
    response `message` field for the exact current limitation.
    """
    return _predictor.predict(request)

# TODO: POST /api/ml/allocate-budget — intelligent budget allocation
# TODO: GET  /api/ml/model-info     — model metadata (version, training date, accuracy)
