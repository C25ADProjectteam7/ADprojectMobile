"""ML API routes — FastAPI router for price prediction and budget allocation"""

from fastapi import APIRouter
from ml.schemas import HotelPriceRequest, HotelPriceResponse
from ml.price_predictor import HotelPricePredictor
from ml.india_schemas import (IndiaHotelPriceRequest, IndiaHotelPriceResponse,
                              IndiaHotelPriceUnavailable)
from ml.india_hotel_price_predictor import IndiaHotelPricePredictor

router = APIRouter(prefix="/api/ml", tags=["Machine Learning"])

# MockHotelPricePredictor remains in ml/price_predictor.py for tests/fallback
# reference, but the live route now serves the trained baseline artifact.
_predictor = HotelPricePredictor()
# V2 India hybrid: B2 historical quantiles for known hotels, CatBoost for the rest.
_india_predictor = IndiaHotelPricePredictor()


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

@router.post("/v2/hotel-price",
             response_model=IndiaHotelPriceResponse | IndiaHotelPriceUnavailable,
             tags=["Machine Learning"])
def predict_india_hotel_price(request: IndiaHotelPriceRequest):
    """
    India hotel fair-price range (prototype market: IN / INR).

    Judges ONE NIGHT at 1 room / 2 adults - the exact context the model was
    trained on. `currentPrice` must be the comparable one-night INR quote from
    the isolated LiteAPI probe, NOT a multi-night booking total, and it never
    enters the model as a feature.

    `predictionSource` reports the route actually taken: "HISTORICAL" (the
    hotel's own historical quantiles) or "ML" (CatBoost quantile model).
    """
    return _india_predictor.predict(
        hotel_name=request.hotelName, current_price=request.currentPrice,
        currency=request.currency, market=request.market,
        booking_date=request.bookingDate, check_in_date=request.checkInDate,
        room_name=request.roomName, board_type=request.boardType,
        board_name=request.boardName, refundable_tag=request.refundableTag,
    )


# TODO: POST /api/ml/allocate-budget — intelligent budget allocation
# TODO: GET  /api/ml/model-info     — model metadata (version, training date, accuracy)
