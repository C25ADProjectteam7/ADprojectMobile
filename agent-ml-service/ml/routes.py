"""ML API routes — FastAPI router for price prediction and budget allocation"""

from fastapi import APIRouter
from ml.schemas import HotelPriceRequest, HotelPriceResponse
from ml.price_predictor import MockHotelPricePredictor

router = APIRouter(prefix="/api/ml", tags=["Machine Learning"])

_predictor = MockHotelPricePredictor()


@router.post("/predict-hotel-price", response_model=HotelPriceResponse)
def predict_hotel_price(request: HotelPriceRequest) -> HotelPriceResponse:
    """
    Predict hotel price per night and total stay cost.

    **Current status:** mock implementation — returns deterministic rule-based
    estimates. Replace MockHotelPricePredictor with a trained model when available.
    Results must not be used for real booking decisions.
    """
    return _predictor.predict(request)

# TODO: POST /api/ml/allocate-budget — intelligent budget allocation
# TODO: GET  /api/ml/model-info     — model metadata (version, training date, accuracy)
