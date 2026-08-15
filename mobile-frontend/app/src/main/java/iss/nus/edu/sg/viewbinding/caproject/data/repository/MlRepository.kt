package iss.nus.edu.sg.viewbinding.caproject.data.repository

import android.content.Context
import com.google.gson.Gson
import iss.nus.edu.sg.viewbinding.caproject.model.HotelPricePrediction
import iss.nus.edu.sg.viewbinding.caproject.network.ApiClient
import iss.nus.edu.sg.viewbinding.caproject.network.ApiFailureKind
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult
import iss.nus.edu.sg.viewbinding.caproject.network.MlApi
import iss.nus.edu.sg.viewbinding.caproject.network.executeApiCall
import iss.nus.edu.sg.viewbinding.caproject.network.model.ml.HotelPricePredictionRequest
import iss.nus.edu.sg.viewbinding.caproject.network.model.ml.HotelPricePredictionResponse
import iss.nus.edu.sg.viewbinding.caproject.network.model.ml.HotelFairPriceRequest
import iss.nus.edu.sg.viewbinding.caproject.network.model.ml.HotelFairPriceResponse
import java.time.LocalDate
import java.util.Locale

class MlRepository(
    private val mlApi: MlApi,
    private val gson: Gson,
) {

    suspend fun predictHotelPrice(
        city: String,
        checkInDate: LocalDate,
        checkOutDate: LocalDate,
        hotelStarRating: Int,
        roomType: String,
        numberOfGuests: Int,
        currency: String,
        bookingDate: LocalDate = LocalDate.now(),
    ): ApiResult<HotelPricePrediction> {
        val request = HotelPricePredictionRequest(
            city = city.trim(),
            checkInDate = checkInDate.toString(),
            checkOutDate = checkOutDate.toString(),
            bookingDate = bookingDate.toString(),
            hotelStarRating = hotelStarRating,
            roomType = roomType.lowercase(Locale.ENGLISH),
            numberOfGuests = numberOfGuests,
            currency = currency.uppercase(Locale.ENGLISH),
        )
        return when (val result = executeApiCall(gson) { mlApi.predictHotelPrice(request) }) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> runCatching { result.value.toPrediction() }
                .fold(
                    onSuccess = { ApiResult.Success(it) },
                    onFailure = { ApiResult.Failure(ApiFailureKind.INVALID_RESPONSE) },
                )
        }
    }

    suspend fun predictHotelFairPrice(
        hotelId: String,
        hotelName: String,
        checkInDate: LocalDate,
        bookingDate: LocalDate = LocalDate.now(),
    ): ApiResult<HotelFairPriceResponse> {
        val request = HotelFairPriceRequest(
            hotelId = hotelId.trim(),
            hotelName = hotelName.trim(),
            bookingDate = bookingDate.toString(),
            checkInDate = checkInDate.toString(),
        )

        return executeApiCall(gson) {
            mlApi.predictHotelFairPrice(request)
        }
    }

    private fun HotelPricePredictionResponse.toPrediction(): HotelPricePrediction {
        val pricePerNight = requireNotNull(predictedPricePerNight)
        val totalPrice = requireNotNull(predictedTotalPrice)
        val nights = requireNotNull(numberOfNights)
        val currencyCode = requireNotNull(currency).trim().uppercase(Locale.ENGLISH)
        val mock = requireNotNull(isMock)
        require(pricePerNight.signum() >= 0 && totalPrice.signum() >= 0)
        require(nights > 0 && currencyCode.length == 3)
        return HotelPricePrediction(
            predictedPricePerNight = pricePerNight,
            predictedTotalPrice = totalPrice,
            numberOfNights = nights,
            currency = currencyCode,
            modelStatus = modelStatus.orEmpty().trim(),
            modelVersion = modelVersion.orEmpty().trim(),
            isMock = mock,
            message = message.orEmpty().trim(),
        )
    }

    companion object {
        fun create(context: Context): MlRepository {
            val applicationContext = context.applicationContext
            return MlRepository(
                mlApi = ApiClient.mlApi(applicationContext),
                gson = ApiClient.gson(),
            )
        }
    }
}
