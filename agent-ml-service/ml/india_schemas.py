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


class IndiaHotelPriceByHotelIdRequest(BaseModel):
    hotelId: str = Field(..., min_length=1)
    hotelName: str = Field(..., min_length=1)
    bookingDate: date
    checkInDate: date


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


class IndiaHotelPriceUnavailable(BaseModel):
    predictionAvailable: Literal[False] = False
    reason: Literal["UNSUPPORTED_MARKET", "UNSUPPORTED_LEAD_TIME",
                    "NO_COMPARABLE_RATE", "INVALID_INPUT", "MODEL_ERROR"]
