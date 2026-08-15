package iss.nus.edu.sg.viewbinding.caproject.network.model.ml

import com.google.gson.annotations.SerializedName
import java.math.BigDecimal

data class HotelPricePredictionRequest(
    val city: String,
    val checkInDate: String,
    val checkOutDate: String,
    val bookingDate: String,
    val hotelStarRating: Int,
    val roomType: String,
    val numberOfGuests: Int,
    val currency: String,
)

data class HotelPricePredictionResponse(
    @SerializedName(value = "predictedPricePerNight", alternate = ["predicted_price_per_night"])
    val predictedPricePerNight: BigDecimal?,
    @SerializedName(value = "predictedTotalPrice", alternate = ["predicted_total_price"])
    val predictedTotalPrice: BigDecimal?,
    @SerializedName(value = "numberOfNights", alternate = ["number_of_nights"])
    val numberOfNights: Int?,
    val currency: String?,
    @SerializedName(value = "modelStatus", alternate = ["model_status"])
    val modelStatus: String?,
    @SerializedName(value = "modelVersion", alternate = ["model_version"])
    val modelVersion: String?,
    @SerializedName(value = "isMock", alternate = ["is_mock"])
    val isMock: Boolean?,
    val message: String?,
)

data class PriceAdviceRequest(
    val city: String,
    val checkInDate: String,
    val checkOutDate: String,
    val roomType: String,
    val numberOfGuests: Int,
    val bookingDate: String,
    val currentPrice: BigDecimal? = null,
)

data class PriceAdviceResponse(
    @SerializedName(value = "predictionAvailable", alternate = ["prediction_available"])
    val predictionAvailable: Boolean?,
    val reason: String? = null,
    val currency: String? = null,
    @SerializedName(value = "modelStatus", alternate = ["model_status"])
    val modelStatus: String? = null,
    @SerializedName(value = "modelVersion", alternate = ["model_version"])
    val modelVersion: String? = null,
    @SerializedName(value = "priceRangePerNight", alternate = ["price_range_per_night"])
    val priceRangePerNight: PriceRangeDto? = null,
    @SerializedName(value = "totalPriceRange", alternate = ["total_price_range"])
    val totalPriceRange: PriceRangeDto? = null,
    @SerializedName(value = "buyTiming", alternate = ["buy_timing"])
    val buyTiming: BuyTimingDto? = null,
    @SerializedName(value = "monthlyCurve", alternate = ["monthly_curve"])
    val monthlyCurve: List<MonthlyPointDto>? = null,
    @SerializedName(value = "cheapestMonth", alternate = ["cheapest_month"])
    val cheapestMonth: MonthlyPointDto? = null,
    @SerializedName(value = "currentTiming", alternate = ["current_timing"])
    val currentTiming: CurrentTimingDto? = null,
    val message: String? = null,
)

data class CurrentTimingDto(
    @SerializedName(value = "currentLeadDays", alternate = ["current_lead_days"])
    val currentLeadDays: Int? = null,
    @SerializedName(value = "currentPricePerNight", alternate = ["current_price_per_night"])
    val currentPricePerNight: BigDecimal? = null,
    @SerializedName(value = "bestPricePerNight", alternate = ["best_price_per_night"])
    val bestPricePerNight: BigDecimal? = null,
    @SerializedName(value = "premiumVsBestPercent", alternate = ["premium_vs_best_percent"])
    val premiumVsBestPercent: Double? = null,
    val verdict: String? = null,
    val message: String? = null,
)

data class PriceRangeDto(
    val p25: BigDecimal? = null,
    val p50: BigDecimal? = null,
    val p75: BigDecimal? = null,
)

data class BuyTimingDto(
    @SerializedName(value = "recommendedLeadDays", alternate = ["recommended_lead_days"])
    val recommendedLeadDays: Int? = null,
    @SerializedName(value = "cheapestPricePerNight", alternate = ["cheapest_price_per_night"])
    val cheapestPricePerNight: BigDecimal? = null,
    @SerializedName(value = "savingVsLastMinutePercent", alternate = ["saving_vs_last_minute_percent"])
    val savingVsLastMinutePercent: Double? = null,
    val message: String? = null,
)

data class MonthlyPointDto(
    val month: Int? = null,
    @SerializedName(value = "p50PerNight", alternate = ["p50_per_night"])
    val p50PerNight: BigDecimal? = null,
)
