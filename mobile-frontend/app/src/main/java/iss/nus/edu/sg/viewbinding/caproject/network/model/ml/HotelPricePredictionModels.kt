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
