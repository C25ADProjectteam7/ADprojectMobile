"""
ML API request/response schemas — Pydantic v2 models
"""

from datetime import date
from typing import Literal
from pydantic import BaseModel, Field, field_validator, model_validator

# Mock stage has no currency conversion — city base prices in price_predictor.py
# are USD only, so only USD requests can be answered honestly. Reject anything
# else rather than silently mislabeling a USD-derived number as another currency.
SUPPORTED_CURRENCIES: frozenset[str] = frozenset({"USD"})


class HotelPriceRequest(BaseModel):
    city: str = Field(..., min_length=1, examples=["Tokyo"])
    check_in_date: date = Field(..., examples=["2026-08-10"])
    check_out_date: date = Field(..., examples=["2026-08-13"])
    booking_date: date = Field(..., examples=["2026-07-31"])
    hotel_star_rating: int = Field(..., ge=1, le=5, examples=[4])
    room_type: Literal["single", "double", "twin", "suite"] = Field(..., examples=["double"])
    number_of_guests: int = Field(..., ge=1, examples=[2])
    currency: str = Field(..., min_length=3, max_length=3, examples=["USD"])

    @field_validator("city")
    @classmethod
    def strip_and_validate_city(cls, v: str) -> str:
        stripped = v.strip()
        if not stripped:
            raise ValueError("city must not be blank")
        return stripped

    @field_validator("currency")
    @classmethod
    def normalize_and_validate_currency(cls, v: str) -> str:
        normalized = v.strip().upper()
        if normalized not in SUPPORTED_CURRENCIES:
            raise ValueError(
                f"currency {v!r} not supported by the mock predictor "
                f"(no conversion available) — supported: {sorted(SUPPORTED_CURRENCIES)}"
            )
        return normalized

    @model_validator(mode="after")
    def validate_dates(self) -> "HotelPriceRequest":
        if self.check_out_date <= self.check_in_date:
            raise ValueError("check_out_date must be after check_in_date")
        if self.booking_date > self.check_in_date:
            raise ValueError("booking_date cannot be later than check_in_date")
        return self


class HotelPriceResponse(BaseModel):
    predicted_price_per_night: float = Field(..., examples=[150.0])
    predicted_total_price: float = Field(..., examples=[450.0])
    number_of_nights: int = Field(..., examples=[3])
    currency: str = Field(..., examples=["USD"])
    model_status: Literal["mock", "baseline", "trained"] = Field(..., examples=["mock"])
    model_version: str = Field(..., examples=["mock-v0"])
    is_mock: bool = Field(..., examples=[True])
    message: str = Field(
        ...,
        examples=["This is a mock prediction. Do not use for real booking decisions."]
    )
