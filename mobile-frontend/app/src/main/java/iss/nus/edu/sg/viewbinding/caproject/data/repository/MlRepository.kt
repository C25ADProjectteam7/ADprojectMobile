package iss.nus.edu.sg.viewbinding.caproject.data.repository

import android.content.Context
import com.google.gson.Gson
import iss.nus.edu.sg.viewbinding.caproject.model.BuyTiming
import iss.nus.edu.sg.viewbinding.caproject.model.CurrentTiming
import iss.nus.edu.sg.viewbinding.caproject.model.HotelCandidate
import iss.nus.edu.sg.viewbinding.caproject.model.HotelFairPrice
import iss.nus.edu.sg.viewbinding.caproject.model.HotelPricePrediction
import iss.nus.edu.sg.viewbinding.caproject.model.PriceAdvice
import iss.nus.edu.sg.viewbinding.caproject.model.PriceRange
import iss.nus.edu.sg.viewbinding.caproject.network.ApiClient
import iss.nus.edu.sg.viewbinding.caproject.network.ApiFailureKind
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult
import iss.nus.edu.sg.viewbinding.caproject.network.MlApi
import iss.nus.edu.sg.viewbinding.caproject.network.executeApiCall
import iss.nus.edu.sg.viewbinding.caproject.network.model.ml.CandidateHotelDto
import iss.nus.edu.sg.viewbinding.caproject.network.model.ml.HotelFairPriceRequest
import iss.nus.edu.sg.viewbinding.caproject.network.model.ml.HotelFairPriceResponse
import iss.nus.edu.sg.viewbinding.caproject.network.model.ml.HotelPricePredictionRequest
import iss.nus.edu.sg.viewbinding.caproject.network.model.ml.HotelPricePredictionResponse
import iss.nus.edu.sg.viewbinding.caproject.network.model.ml.PriceAdviceRequest
import iss.nus.edu.sg.viewbinding.caproject.network.model.ml.PriceAdviceResponse
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

/** Either a usable V3 verdict, or the documented reason there isn't one. */
sealed interface HotelFairPriceOutcome {
    data class Available(val value: HotelFairPrice) : HotelFairPriceOutcome
    data class Unavailable(val reason: String) : HotelFairPriceOutcome
}

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

    /**
     * India hotel fair price (V3), including this trip's candidate context.
     *
     * `candidates` carries identity only; the ML service re-probes each hotel
     * on its own one-night INR contract, so no Agent price is ever sent. The
     * hotel being judged may appear in the list - the backend removes it.
     *
     * A well-formed "predictionAvailable: false" is NOT a failure: it is the
     * documented answer for an unsupported market, lead time or missing rate,
     * and is surfaced as [HotelFairPriceOutcome.Unavailable] so the caller can
     * fall back to price advice and record why.
     */
    suspend fun predictHotelFairPrice(
        hotelId: String,
        hotelName: String,
        checkInDate: LocalDate,
        candidates: List<HotelCandidate>,
        bookingDate: LocalDate = LocalDate.now(),
    ): ApiResult<HotelFairPriceOutcome> {
        val request = HotelFairPriceRequest(
            hotelId = hotelId.trim(),
            hotelName = hotelName.trim(),
            bookingDate = bookingDate.toString(),
            checkInDate = checkInDate.toString(),
            candidateHotels = candidates.map {
                CandidateHotelDto(hotelId = it.hotelId.trim(), hotelName = it.hotelName?.trim())
            },
        )
        return when (val result = executeApiCall(gson) { mlApi.predictHotelFairPrice(request) }) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> runCatching { result.value.toOutcome() }
                .fold(
                    onSuccess = { ApiResult.Success(it) },
                    onFailure = { ApiResult.Failure(ApiFailureKind.INVALID_RESPONSE) },
                )
        }
    }

    private fun HotelFairPriceResponse.toOutcome(): HotelFairPriceOutcome {
        if (predictionAvailable != true) {
            return HotelFairPriceOutcome.Unavailable(
                reason.orEmpty().trim().ifBlank { "UNAVAILABLE" },
            )
        }
        val low = requireNotNull(decisionLow)
        val high = requireNotNull(decisionHigh)
        val current = requireNotNull(currentComparablePrice)
        val level = requireNotNull(priceLevel).trim().uppercase(Locale.ENGLISH)
        require(level in setOf("CHEAP", "FAIR", "EXPENSIVE"))
        require(low <= high && current.signum() > 0)
        val currencyCode = requireNotNull(currency).trim().uppercase(Locale.ENGLISH)
        require(currencyCode.length == 3)
        return HotelFairPriceOutcome.Available(
            HotelFairPrice(
                source = predictionSource.orEmpty().trim(),
                // These are the FINAL numbers - already context-adjusted by the
                // backend when it applied one. Never re-scale them here.
                fairPriceP25 = requireNotNull(fairPriceP25),
                fairPriceP50 = requireNotNull(fairPriceP50),
                fairPriceP75 = requireNotNull(fairPriceP75),
                decisionLow = low,
                decisionHigh = high,
                currentComparablePrice = current,
                priceLevel = level,
                currency = currencyCode,
                contextAdjustmentApplied = contextAdjustmentApplied == true,
                modelVersion = modelVersion.orEmpty().trim(),
            ),
        )
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
