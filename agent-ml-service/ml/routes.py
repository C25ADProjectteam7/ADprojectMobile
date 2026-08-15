"""ML API routes — FastAPI router for price prediction and budget allocation"""

from fastapi import APIRouter
from ml.schemas import HotelPriceRequest, HotelPriceResponse, PriceAdviceRequest, PriceAdviceResponse
from ml.price_predictor import HotelPricePredictor
from ml.price_advisor import PriceAdvisor
from ml.india_schemas import (
    IndiaHotelPriceRequest,
    IndiaHotelPriceByHotelIdRequest,
    IndiaHotelPriceResponse,
    IndiaHotelPriceUnavailable,
)
from ml.india_hotel_price_predictor import IndiaHotelPricePredictor
from ml.india_liteapi_probe import get_fair_price_probe

router = APIRouter(prefix="/api/ml", tags=["Machine Learning"])

# MockHotelPricePredictor remains in ml/price_predictor.py for tests/fallback
# reference, but the live route now serves the trained baseline artifact.
_predictor = HotelPricePredictor()
# V2 India hybrid: B2 historical quantiles for known hotels, CatBoost for the rest.
_india_predictor = IndiaHotelPricePredictor()
# Price advisor: P25/P50/P75 quantile model + lead-time/month scan.
_advisor = PriceAdvisor()


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

@router.post(
    "/v2/hotel-price/by-hotel-id",
    response_model=IndiaHotelPriceResponse | IndiaHotelPriceUnavailable,
    tags=["Machine Learning"],
)
async def predict_india_hotel_price_by_hotel_id(
    request: IndiaHotelPriceByHotelIdRequest,
):
    # 1. Ask LiteAPI for a comparable quote:
    #    same hotel, 1 night, 1 room, 2 adults, INR.
    probe = await get_fair_price_probe(
        hotel_id=request.hotelId,
        hotel_name=request.hotelName,
        check_in=request.checkInDate.isoformat(),
    )

    # 2. No valid comparable LiteAPI rate.
    if not probe.get("available"):
        return {
            "predictionAvailable": False,
            "reason": probe.get("reason", "NO_COMPARABLE_RATE"),
        }

    # 3. Country must come from LiteAPI, not from Android/Spring.
    country = (probe.get("country") or "").strip()
    if country.casefold() in {"india", "in", "ind"}:
        market = "IN"
    else:
        market = country

    # 4. Feed the real LiteAPI quote/context into the existing predictor.
    return _india_predictor.predict(
        hotel_name=probe["hotelName"],
        current_price=probe["comparableOneNightPrice"],
        currency=probe.get("currency") or "INR",
        market=market,
        booking_date=request.bookingDate,
        check_in_date=request.checkInDate,
        room_name=probe.get("roomName"),
        board_type=probe.get("boardType"),
        board_name=probe.get("boardName"),
        refundable_tag=probe.get("refundableTag"),
    )


@router.post("/v2/price-advice", response_model=PriceAdviceResponse,
             tags=["Machine Learning"])
def price_advice(request: PriceAdviceRequest) -> PriceAdviceResponse:
    """
    Model-driven price RANGE + best-buy timing advice for a planned stay.

    Answers: expected per-night range (P25/P50/P75), when to book (lead-time
    scan -> cheapest point), and which month is cheapest. All from scanning
    a quantile model trained on 72k real hotel bookings - no external
    scraping. The model is city-agnostic (dataset has no city field); the
    response message states this limitation.
    """
    return PriceAdviceResponse(**_advisor.advise(
        check_in_date=request.check_in_date,
        check_out_date=request.check_out_date,
        number_of_guests=request.number_of_guests,
        room_type=request.room_type,
        booking_date=request.booking_date,
        current_price=request.current_price,
    ))


# TODO: POST /api/ml/allocate-budget — intelligent budget allocation
# TODO: GET  /api/ml/model-info     — model metadata (version, training date, accuracy)
