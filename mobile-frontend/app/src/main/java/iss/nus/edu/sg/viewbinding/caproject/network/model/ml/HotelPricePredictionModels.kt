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

data class HotelFairPriceRequest(
    val hotelId: String,
    val hotelName: String,
    val bookingDate: String,
    val checkInDate: String,
)

data class HotelFairPriceResponse(
    val predictionAvailable: Boolean,
    val reason: String?,
    val predictionSource: String?,
    val fairPriceP25: BigDecimal?,
    val fairPriceP50: BigDecimal?,
    val fairPriceP75: BigDecimal?,
    val decisionLow: BigDecimal?,
    val decisionHigh: BigDecimal?,
    val currentComparablePrice: BigDecimal?,
    val priceLevel: String?,
    val currency: String?,
)
