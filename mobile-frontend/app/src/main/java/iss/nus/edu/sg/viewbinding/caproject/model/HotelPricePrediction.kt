package iss.nus.edu.sg.viewbinding.caproject.model

import java.math.BigDecimal

data class HotelPricePrediction(
    val predictedPricePerNight: BigDecimal,
    val predictedTotalPrice: BigDecimal,
    val numberOfNights: Int,
    val currency: String,
    val modelStatus: String,
    val modelVersion: String,
    val isMock: Boolean,
    val message: String,
)

/** Model-driven price RANGE + best-buy timing advice for a planned stay
 * (POST /api/ml/v2/price-advice). */
data class PriceAdvice(
    val priceRangePerNight: PriceRange,
    val totalPriceRange: PriceRange,
    val buyTiming: BuyTiming,
    val currentTiming: CurrentTiming?,
    val cheapestMonth: Int?,
    val cheapestMonthPrice: BigDecimal?,
    val currency: String,
    val modelStatus: String,
    val modelVersion: String,
    val message: String,
)

data class PriceRange(
    val p25: BigDecimal,
    val p50: BigDecimal,
    val p75: BigDecimal,
)

data class BuyTiming(
    val recommendedLeadDays: Int?,
    val cheapestPricePerNight: BigDecimal,
    val savingVsLastMinutePercent: Double?,
    val message: String,
)

/** Is booking RIGHT NOW a good time? Compares today's lead time against the
 * model's pricing curve (verdict: GOOD_TIME / OK / TOO_LATE). */
data class CurrentTiming(
    val currentLeadDays: Int?,
    val currentPricePerNight: BigDecimal?,
    val bestPricePerNight: BigDecimal?,
    val premiumVsBestPercent: Double?,
    val verdict: String,
    val message: String,
)
