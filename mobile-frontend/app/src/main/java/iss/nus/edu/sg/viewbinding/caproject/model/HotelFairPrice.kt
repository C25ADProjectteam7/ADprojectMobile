package iss.nus.edu.sg.viewbinding.caproject.model

import java.math.BigDecimal

/**
 * India hotel fair-price verdict (V3), as returned by the ML service.
 *
 * The band here is the FINAL one: when the backend applied a current-trip
 * candidate-context adjustment it already did so, so the app displays these
 * numbers as-is and must never re-scale them. `contextAdjustmentApplied` is
 * carried purely so the card can say the comparison used this trip's other
 * options; the factor itself is deliberately not exposed to the UI.
 *
 * This is NOT the hotel's booking cost. It is a comparable one-night,
 * two-adult benchmark in the market's own currency, and it lives alongside the
 * Agent's whole-stay booking quote rather than replacing it.
 */
data class HotelFairPrice(
    val source: String,
    val fairPriceP25: BigDecimal,
    val fairPriceP50: BigDecimal,
    val fairPriceP75: BigDecimal,
    val decisionLow: BigDecimal,
    val decisionHigh: BigDecimal,
    val currentComparablePrice: BigDecimal,
    val priceLevel: String,
    val currency: String,
    val contextAdjustmentApplied: Boolean,
    val modelVersion: String,
)

/** One hotel the Agent offered for the same trip. Identity only - no prices. */
data class HotelCandidate(
    val hotelId: String,
    val hotelName: String?,
)
