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
from ml.india_liteapi_probe import get_fair_price_probe, get_fair_price_probes_batch
from ml.india_context_adjustment import (
    REASON_NO_CONTEXT_HOTELS, REASON_PROBE_FAILED,
    compute_context_factor, context_ratio, no_adjustment,
)

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
    def _predict(context=None):
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
            context=context,
        )

    prediction = _predict()

    # 5. Current-trip candidate context, ML path only. Computing the unadjusted
    #    prediction FIRST means a HISTORICAL hotel costs no extra LiteAPI calls:
    #    the adjustment is excluded there by design, so there is nothing to probe.
    if (not prediction.get("predictionAvailable")
            or prediction.get("predictionSource") != "ML"
            or not request.candidateHotels):
        return prediction

    context = await _candidate_context(
        target_hotel_id=request.hotelId,
        candidates=request.candidateHotels,
        booking_date=request.bookingDate,
        check_in_date=request.checkInDate,
    )
    return _predict(context)


async def _candidate_context(*, target_hotel_id, candidates,
                             booking_date, check_in_date) -> dict:
    """Median live/raw ratio across the OTHER candidate hotels of this trip.

    The hotel being judged is removed before anything is probed, so its own
    current price cannot influence the band it is judged against - the whole
    point of the exercise. Only candidates that route to the ML path count: a
    HISTORICAL candidate's quantiles come from a different source and mixing
    them in would blend two incompatible baselines.
    """
    target = str(target_hotel_id)
    seen, context_ids = {target}, []
    for candidate in candidates:
        hid = str(candidate.hotelId)
        if hid not in seen:
            seen.add(hid)
            context_ids.append(hid)
    if not context_ids:
        return no_adjustment(REASON_NO_CONTEXT_HOTELS)

    # ONE /hotels/rates + ONE /data/hotels for the whole set, never per hotel.
    try:
        probes = await get_fair_price_probes_batch(context_ids, check_in_date.isoformat())
    except Exception:
        return no_adjustment(REASON_PROBE_FAILED)
    if not probes:
        return no_adjustment(REASON_NO_CONTEXT_HOTELS)

    ratios, detail = [], []
    for hid in context_ids:
        p = probes.get(hid)
        if not p:
            detail.append({"hotelId": hid, "used": False, "reason": "NO_COMPARABLE_RATE"})
            continue
        country = (p.get("country") or "").strip()
        candidate_market = "IN" if country.casefold() in {"india", "in", "ind"} else country
        pred = _india_predictor.predict(
            hotel_name=p.get("hotelName") or hid,
            current_price=p["comparableOneNightPrice"],
            currency=p.get("currency") or "INR",
            market=candidate_market,
            booking_date=booking_date,
            check_in_date=check_in_date,
            room_name=p.get("roomName"), board_type=p.get("boardType"),
            board_name=p.get("boardName"), refundable_tag=p.get("refundableTag"),
            city=p.get("city"), rating=p.get("rating"),
            review_count=p.get("reviewCount"), stars=p.get("stars"),
            chain=p.get("chain"), hotel_type_id=p.get("hotelTypeId"),
            facility_ids=p.get("facilityIds"),
        )
        if not pred.get("predictionAvailable"):
            detail.append({"hotelId": hid, "used": False,
                           "reason": pred.get("reason", "UNAVAILABLE")})
            continue
        if pred["predictionSource"] != "ML":
            detail.append({"hotelId": hid, "used": False, "reason": "HISTORICAL_CANDIDATE"})
            continue
        ratio = context_ratio(p["comparableOneNightPrice"], pred["rawFairPriceP50"])
        if ratio is None:
            detail.append({"hotelId": hid, "used": False, "reason": "INVALID_RATIO"})
            continue
        ratios.append(ratio)
        detail.append({"hotelId": hid, "used": True, "ratio": round(ratio, 4)})

    return compute_context_factor(ratios, detail)


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
