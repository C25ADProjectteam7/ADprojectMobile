package iss.nus.edu.sg.viewbinding.caproject.data.repository

import android.content.Context
import com.google.gson.Gson
import iss.nus.edu.sg.viewbinding.caproject.model.BuyTiming
import iss.nus.edu.sg.viewbinding.caproject.model.CurrentTiming
import iss.nus.edu.sg.viewbinding.caproject.model.HotelPricePrediction
import iss.nus.edu.sg.viewbinding.caproject.model.PriceAdvice
import iss.nus.edu.sg.viewbinding.caproject.model.PriceRange
import iss.nus.edu.sg.viewbinding.caproject.network.ApiClient
import iss.nus.edu.sg.viewbinding.caproject.network.ApiFailureKind
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult
import iss.nus.edu.sg.viewbinding.caproject.network.MlApi
import iss.nus.edu.sg.viewbinding.caproject.network.executeApiCall
import iss.nus.edu.sg.viewbinding.caproject.network.model.ml.HotelPricePredictionRequest
import iss.nus.edu.sg.viewbinding.caproject.network.model.ml.HotelPricePredictionResponse
import iss.nus.edu.sg.viewbinding.caproject.network.model.ml.PriceAdviceRequest
import iss.nus.edu.sg.viewbinding.caproject.network.model.ml.PriceAdviceResponse
import java.math.BigDecimal
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

    suspend fun getPriceAdvice(
        city: String,
        checkInDate: LocalDate,
        checkOutDate: LocalDate,
        roomType: String,
        numberOfGuests: Int,
        bookingDate: LocalDate = LocalDate.now(),
        currentPrice: BigDecimal? = null,
    ): ApiResult<PriceAdvice> {
        val request = PriceAdviceRequest(
            city = city.trim(),
            checkInDate = checkInDate.toString(),
            checkOutDate = checkOutDate.toString(),
            roomType = roomType.lowercase(Locale.ENGLISH),
            numberOfGuests = numberOfGuests,
            bookingDate = bookingDate.toString(),
            currentPrice = currentPrice,
        )
        return when (val result = executeApiCall(gson) { mlApi.priceAdvice(request) }) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> runCatching { result.value.toAdvice() }
                .fold(
                    onSuccess = { ApiResult.Success(it) },
                    onFailure = { ApiResult.Failure(ApiFailureKind.INVALID_RESPONSE) },
                )
        }
    }

    private fun PriceAdviceResponse.toAdvice(): PriceAdvice {
        val available = requireNotNull(predictionAvailable)
        require(available)
        val perNight = requireNotNull(priceRangePerNight)
        val total = requireNotNull(totalPriceRange)
        val timing = requireNotNull(buyTiming)
        val range = PriceRange(
            p25 = requireNotNull(perNight.p25),
            p50 = requireNotNull(perNight.p50),
            p75 = requireNotNull(perNight.p75),
        )
        val totalRange = PriceRange(
            p25 = requireNotNull(total.p25),
            p50 = requireNotNull(total.p50),
            p75 = requireNotNull(total.p75),
        )
        return PriceAdvice(
            priceRangePerNight = range,
            totalPriceRange = totalRange,
            buyTiming = BuyTiming(
                recommendedLeadDays = timing.recommendedLeadDays,
                cheapestPricePerNight = requireNotNull(timing.cheapestPricePerNight),
                savingVsLastMinutePercent = timing.savingVsLastMinutePercent,
                message = timing.message.orEmpty().trim(),
            ),
            currentTiming = currentTiming?.let { timing ->
                CurrentTiming(
                    currentLeadDays = timing.currentLeadDays,
                    currentPricePerNight = timing.currentPricePerNight,
                    bestPricePerNight = timing.bestPricePerNight,
                    premiumVsBestPercent = timing.premiumVsBestPercent,
                    verdict = timing.verdict.orEmpty().trim(),
                    message = timing.message.orEmpty().trim(),
                )
            },
            cheapestMonth = cheapestMonth?.month,
            cheapestMonthPrice = cheapestMonth?.p50PerNight,
            currency = currency.orEmpty().trim().ifBlank { "USD" }.uppercase(Locale.ENGLISH),
            modelStatus = modelStatus.orEmpty().trim(),
            modelVersion = modelVersion.orEmpty().trim(),
            message = message.orEmpty().trim(),
        )
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
