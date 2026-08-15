"""ML API routes — FastAPI router for price prediction and budget allocation"""

from fastapi import APIRouter
from ml.schemas import HotelPriceRequest, HotelPriceResponse
from ml.price_predictor import HotelPricePredictor
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
        # V3 unseen-hotel signals, straight from LiteAPI - never user-supplied.
        # The comparable quote is passed as current_price above and is compared
        # to the prediction afterwards; it never enters the feature row.
        city=probe.get("city"),
        rating=probe.get("rating"),
        review_count=probe.get("reviewCount"),
        stars=probe.get("stars"),
        chain=probe.get("chain"),
        hotel_type_id=probe.get("hotelTypeId"),
        facility_ids=probe.get("facilityIds"),
    )


# TODO: POST /api/ml/allocate-budget — intelligent budget allocation
# TODO: GET  /api/ml/model-info     — model metadata (version, training date, accuracy)
