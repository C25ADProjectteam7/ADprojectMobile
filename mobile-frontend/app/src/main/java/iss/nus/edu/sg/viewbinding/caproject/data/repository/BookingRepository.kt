package iss.nus.edu.sg.viewbinding.caproject.data.repository

import android.content.Context
import com.google.gson.Gson
import iss.nus.edu.sg.viewbinding.caproject.model.BookingRecord
import iss.nus.edu.sg.viewbinding.caproject.model.ItineraryItem
import iss.nus.edu.sg.viewbinding.caproject.model.TripRequestData
import iss.nus.edu.sg.viewbinding.caproject.network.ApiClient
import iss.nus.edu.sg.viewbinding.caproject.network.ApiFailureKind
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult
import iss.nus.edu.sg.viewbinding.caproject.network.BookingApi
import iss.nus.edu.sg.viewbinding.caproject.network.executeApiCall
import iss.nus.edu.sg.viewbinding.caproject.network.model.booking.BookingRequest
import iss.nus.edu.sg.viewbinding.caproject.network.model.booking.BookingResponse
import iss.nus.edu.sg.viewbinding.caproject.network.model.common.MessageResponse
import java.time.format.DateTimeFormatter
import java.util.Locale

class BookingRepository(
    private val bookingApi: BookingApi,
    private val gson: Gson,
) {

    suspend fun getBookings(): ApiResult<List<BookingRecord>> {
        return executeAndMap({ bookingApi.getBookings() }) { responses ->
            responses.map { it.toBookingRecord() }
        }
    }

    suspend fun getTripBookings(tripId: Long): ApiResult<List<BookingRecord>> {
        return when (val result = getBookings()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> ApiResult.Success(
                result.value.filter { it.tripId == tripId }.sortedBy(BookingRecord::id),
            )
        }
    }

    suspend fun getBooking(bookingId: Long): ApiResult<BookingRecord> {
        return executeAndMap({ bookingApi.getBooking(bookingId) }) { it.toBookingRecord() }
    }

    suspend fun getBookingDetails(bookingIds: List<Long>): ApiResult<List<BookingRecord>> {
        val bookings = mutableListOf<BookingRecord>()
        bookingIds.distinct().forEach { bookingId ->
            when (val result = getBooking(bookingId)) {
                is ApiResult.Failure -> return result
                is ApiResult.Success -> bookings += result.value
            }
        }
        return ApiResult.Success(bookings.sortedBy(BookingRecord::id))
    }

    suspend fun confirmTripBookings(
        trip: TripRequestData,
        itineraryItems: List<ItineraryItem>,
    ): ApiResult<List<BookingRecord>> {
        val tripId = trip.remoteId ?: return invalidResponse()
        val requests = buildRequests(trip, itineraryItems)
        if (requests.isEmpty()) {
            return ApiResult.Failure(ApiFailureKind.VALIDATION)
        }

        val existing = when (val result = getTripBookings(tripId)) {
            is ApiResult.Failure -> return result
            is ApiResult.Success -> result.value.toMutableList()
        }
        val confirmed = mutableListOf<BookingRecord>()

        requests.forEach { request ->
            val reusable = existing
                .filter { it.type.equals(request.type, ignoreCase = true) }
                .filterNot {
                    it.status.equals(BookingRecord.STATUS_CANCELLED, ignoreCase = true) ||
                        it.status.equals(BookingRecord.STATUS_FAILED, ignoreCase = true)
                }
                .maxByOrNull(BookingRecord::id)
            if (reusable != null) {
                confirmed += reusable
                return@forEach
            }

            when (val result = createBooking(tripId, request)) {
                is ApiResult.Failure -> return result
                is ApiResult.Success -> {
                    existing += result.value
                    confirmed += result.value
                }
            }
        }
        return ApiResult.Success(confirmed.sortedBy(BookingRecord::id))
    }

    suspend fun cancelBooking(bookingId: Long): ApiResult<MessageResponse> {
        return executeApiCall(gson) { bookingApi.cancelBooking(bookingId) }
    }

    suspend fun cancelBookings(bookings: List<BookingRecord>): ApiResult<Unit> {
        bookings.filterNot { it.isCancelled }.forEach { booking ->
            when (val result = cancelBooking(booking.id)) {
                is ApiResult.Failure -> return result
                is ApiResult.Success -> Unit
            }
        }
        return ApiResult.Success(Unit)
    }

    private suspend fun createBooking(
        tripId: Long,
        request: BookingRequest,
    ): ApiResult<BookingRecord> {
        return executeAndMap(
            request = { bookingApi.createBooking(tripId, request) },
            mapper = { it.toBookingRecord() },
        )
    }

    private fun buildRequests(
        trip: TripRequestData,
        itineraryItems: List<ItineraryItem>,
    ): List<BookingRequest> {
        val tripId = trip.remoteId ?: return emptyList()
        return listOf(BookingRecord.TYPE_FLIGHT, BookingRecord.TYPE_HOTEL).mapNotNull { type ->
            itineraryItems.firstOrNull { it.type.equals(type, ignoreCase = true) }?.let { item ->
                BookingRequest(
                    type = type,
                    bookingRef = item.bookingRef?.takeIf(String::isNotBlank)
                        ?: mockReference(type, tripId, trip),
                    price = item.price,
                    currency = item.currency?.takeIf(String::isNotBlank) ?: "SGD",
                )
            }
        }
    }

    private fun mockReference(type: String, tripId: Long, trip: TripRequestData): String {
        val typeCode = if (type == BookingRecord.TYPE_FLIGHT) "FLT" else "HTL"
        val date = trip.startDate.format(DateTimeFormatter.BASIC_ISO_DATE)
        return "MOCK-$typeCode-$tripId-$date"
    }

    private suspend fun <Network, Domain> executeAndMap(
        request: suspend () -> Network,
        mapper: (Network) -> Domain,
    ): ApiResult<Domain> {
        return when (val result = executeApiCall(gson, request)) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> runCatching { mapper(result.value) }
                .fold(
                    onSuccess = { ApiResult.Success(it) },
                    onFailure = { invalidResponse() },
                )
        }
    }

    private fun BookingResponse.toBookingRecord(): BookingRecord {
        require(id > 0 && tripId > 0 && userId > 0)
        val normalizedType = type.trim().uppercase(Locale.ENGLISH)
        require(normalizedType in setOf(BookingRecord.TYPE_FLIGHT, BookingRecord.TYPE_HOTEL))
        val normalizedStatus = status.trim().uppercase(Locale.ENGLISH)
        require(normalizedStatus.isNotBlank())
        return BookingRecord(
            id = id,
            tripId = tripId,
            type = normalizedType,
            bookingRef = bookingRef?.takeIf(String::isNotBlank),
            price = price,
            currency = currency?.takeIf(String::isNotBlank)?.uppercase(Locale.ENGLISH),
            status = normalizedStatus,
        )
    }

    private fun invalidResponse() = ApiResult.Failure(ApiFailureKind.INVALID_RESPONSE)

    companion object {
        fun create(context: Context): BookingRepository {
            val applicationContext = context.applicationContext
            return BookingRepository(
                bookingApi = ApiClient.bookingApi(applicationContext),
                gson = ApiClient.gson(),
            )
        }
    }
}
