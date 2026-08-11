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
