"""
Request/response schemas for the India hotel fair-price endpoint (V2).

`currentPrice` MUST be the comparable ONE-NIGHT INR quote produced by the
isolated LiteAPI probe (1 room / 2 adults / 1 night) - never a multi-night
booking total. The model judges the first night only.
"""
from datetime import date
from typing import Literal, Optional

from pydantic import BaseModel, Field, field_validator


class IndiaHotelPriceRequest(BaseModel):
    hotelName: str = Field(..., min_length=1, examples=["Hotel Sea Princess"])
    currentPrice: float = Field(..., gt=0, allow_inf_nan=False, examples=[4500],
                                description="Comparable ONE-NIGHT INR quote, not a stay total; "
                                            "must be finite and > 0")
    currency: str = Field("INR", examples=["INR"])
    market: str = Field("IN", examples=["IN"])
    bookingDate: date = Field(..., examples=["2026-08-14"])
    checkInDate: date = Field(..., examples=["2026-09-01"])
    roomName: Optional[str] = Field(None, examples=["Deluxe Double Room"])
    boardType: Optional[str] = Field(None, examples=["RO"])
    boardName: Optional[str] = Field(None, examples=["Room Only"])
    refundableTag: Optional[str] = Field(None, examples=["RFN"])

    @field_validator("hotelName")
    @classmethod
    def _strip(cls, v: str) -> str:
        s = v.strip()
        if not s:
            raise ValueError("hotelName must not be blank")
        return s


class CandidateHotel(BaseModel):
    """One hotel the Agent offered for this same trip. IDENTITY ONLY.

    No price is accepted on purpose. The Agent quotes whole-stay USD totals for
    the traveller's real dates; the fair-price service re-probes every hotel on
    its own one-night INR contract. Accepting the Agent's figure would mean
    comparing two different measurements.
    """
    hotelId: str = Field(..., min_length=1)
    hotelName: Optional[str] = None


class IndiaHotelPriceByHotelIdRequest(BaseModel):
    hotelId: str = Field(..., min_length=1)
    hotelName: str = Field(..., min_length=1)
    bookingDate: date
    checkInDate: date
    # Optional and additive: omit it and the response is exactly what it was
    # before candidate context existed.
    candidateHotels: list[CandidateHotel] = Field(
        default_factory=list, max_length=10,
        description="Hotels offered for this same trip, used as current-trip "
                    "candidate context. The hotel being judged is ignored if "
                    "present. Never a market price - see "
                    "ml/india_context_adjustment.py.")


class IndiaHotelPriceResponse(BaseModel):
    predictionAvailable: Literal[True] = True
    predictionSource: Literal["ML", "HISTORICAL"]
    modelVersion: str
    fairPriceP25: float
    fairPriceP50: float
    fairPriceP75: float
    decisionLow: float
    decisionHigh: float
    currentComparablePrice: float
    priceLevel: Literal["CHEAP", "FAIR", "EXPENSIVE"]
    currency: str
    market: str
    comparisonBasis: str
    comparisonOfferSelection: str
    hotelMatchedHistorically: bool
    # --- V2.1 audit fields. Additive only: existing consumers ignore them. ---
    priceBasis: str = Field("LITEAPI_NATIVE_2026_SNAPSHOT",
                            description="TEMPORALLY_ADJUSTED_2026_07_REFERENCE on the "
                                        "HISTORICAL path (2020 data rebased); "
                                        "LITEAPI_NATIVE_2026_SNAPSHOT on the ML path")
    temporalAdjustmentFactor: float = Field(
        1.0, description="1.3707 on the HISTORICAL path (MoSPI hotel-lodging CPI, "
                         "2020 -> 2026). 1.0 on the ML path: V3 was trained on "
                         "2026 LiteAPI-native rates and needs no rebasing. The "
                         "current quote is never adjusted on either path.")
    # --- Current-trip candidate context. Additive; fairPrice*/decision* above
    #     remain the final numbers to display. ---
    rawFairPriceP25: Optional[float] = None
    rawFairPriceP50: Optional[float] = None
    rawFairPriceP75: Optional[float] = None
    rawDecisionLow: Optional[float] = None
    rawDecisionHigh: Optional[float] = None
    contextAdjustmentApplied: bool = Field(
        False, description="True only on the ML path with at least 2 valid ML "
                           "context hotels.")
    contextAdjustmentFactor: float = Field(
        1.0, description="Median of liveComparablePrice/rawFairPriceP50 across the "
                         "OTHER candidate hotels of this trip, clamped to "
                         "[0.70, 1.30]. The hotel being judged never contributes.")
    contextAdjustmentRawFactor: Optional[float] = Field(
        None, description="The median before clamping, for audit.")
    contextAdjustmentClamped: bool = False
    contextAdjustmentReason: str = "NO_CANDIDATES"
    contextAdjustmentBasis: str = Field(
        "CURRENT_TRIP_CANDIDATE_CONTEXT",
        description="Scope of the adjustment. It describes this trip's candidate "
                    "set only and is NOT a market price.")
    validContextHotelCount: int = 0


class IndiaHotelPriceUnavailable(BaseModel):
    predictionAvailable: Literal[False] = False
    reason: Literal["UNSUPPORTED_MARKET", "UNSUPPORTED_LEAD_TIME",
                    "NO_COMPARABLE_RATE", "INVALID_INPUT", "MODEL_ERROR"]
