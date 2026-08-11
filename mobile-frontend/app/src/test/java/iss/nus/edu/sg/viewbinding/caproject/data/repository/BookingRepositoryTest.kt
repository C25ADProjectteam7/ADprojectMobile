package iss.nus.edu.sg.viewbinding.caproject.data.repository

import com.google.gson.Gson
import com.google.gson.JsonParser
import iss.nus.edu.sg.viewbinding.caproject.model.BookingRecord
import iss.nus.edu.sg.viewbinding.caproject.model.ItineraryItem
import iss.nus.edu.sg.viewbinding.caproject.model.ItineraryItemState
import iss.nus.edu.sg.viewbinding.caproject.model.TripRequestData
import iss.nus.edu.sg.viewbinding.caproject.network.ApiFailureKind
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult
import iss.nus.edu.sg.viewbinding.caproject.network.BookingApi
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class BookingRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: BookingRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val gson = Gson()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
        repository = BookingRepository(
            bookingApi = retrofit.create(BookingApi::class.java),
            gson = gson,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun confirmTripCreatesFlightAndHotelWithExactSwaggerRequests() = runBlocking {
        server.enqueue(jsonResponse("[]"))
        server.enqueue(bookingResponse(11, "FLIGHT", "AGENT-FLT-1", "412.50"))
        server.enqueue(bookingResponse(12, "HOTEL", "MOCK-HTL-7-20260818", "288.00"))

        val result = repository.confirmTripBookings(londonTrip(), itineraryItems())

        assertTrue(result is ApiResult.Success)
        assertEquals(listOf(11L, 12L), (result as ApiResult.Success).value.map { it.id })
        assertEquals("/api/bookings", server.takeRequest().path)

        val flightRequest = server.takeRequest()
        val flightBody = JsonParser.parseString(flightRequest.body.readUtf8()).asJsonObject
        assertEquals("POST", flightRequest.method)
        assertEquals("/api/bookings/7", flightRequest.path)
        assertEquals("FLIGHT", flightBody.get("type").asString)
        assertEquals("AGENT-FLT-1", flightBody.get("bookingRef").asString)
        assertEquals(BigDecimal("412.50"), flightBody.get("price").asBigDecimal)
        assertEquals("SGD", flightBody.get("currency").asString)

        val hotelRequest = server.takeRequest()
        val hotelBody = JsonParser.parseString(hotelRequest.body.readUtf8()).asJsonObject
        assertEquals("/api/bookings/7", hotelRequest.path)
        assertEquals("HOTEL", hotelBody.get("type").asString)
        assertEquals("MOCK-HTL-7-20260818", hotelBody.get("bookingRef").asString)
    }

    @Test
    fun confirmTripReusesActiveBookingAndOnlyRecreatesCancelledType() = runBlocking {
        server.enqueue(
            jsonResponse(
                """[
                    {"id":11,"tripId":7,"userId":2,"type":"FLIGHT","bookingRef":"EXISTING-FLT","price":412.50,"currency":"SGD","status":"CONFIRMED"},
                    {"id":12,"tripId":7,"userId":2,"type":"HOTEL","bookingRef":"OLD-HTL","price":288.00,"currency":"SGD","status":"CANCELLED"}
                ]""".trimIndent(),
            ),
        )
        server.enqueue(bookingResponse(13, "HOTEL", "MOCK-HTL-7-20260818", "288.00"))

        val result = repository.confirmTripBookings(londonTrip(), itineraryItems())

        val bookings = (result as ApiResult.Success).value
        assertEquals(listOf(11L, 13L), bookings.map { it.id })
        assertEquals(2, server.requestCount)
        assertEquals("GET", server.takeRequest().method)
        val recreatedHotel = server.takeRequest()
        assertEquals("POST", recreatedHotel.method)
        assertEquals("HOTEL", JsonParser.parseString(recreatedHotel.body.readUtf8())
            .asJsonObject.get("type").asString)
    }

    @Test
    fun detailAndCancelUseExactBookingPaths() = runBlocking {
        server.enqueue(bookingResponse(11, "FLIGHT", "AGENT-FLT-1", "412.50"))
        server.enqueue(jsonResponse("""{"message":"Booking cancelled"}"""))

        val detail = repository.getBookingDetails(listOf(11))
        val detailRequest = server.takeRequest()
        assertTrue(detail is ApiResult.Success)
        assertEquals("GET", detailRequest.method)
        assertEquals("/api/bookings/11", detailRequest.path)

        val records = (detail as ApiResult.Success).value
        assertTrue(repository.cancelBookings(records) is ApiResult.Success)
        val cancelRequest = server.takeRequest()
        assertEquals("PUT", cancelRequest.method)
        assertEquals("/api/bookings/11/cancel", cancelRequest.path)
    }

    @Test
    fun malformedBookingResponseIsRejected() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"id":0,"tripId":7,"userId":2,"type":"TRAIN","status":"CONFIRMED"}""",
            ),
        )

        val result = repository.getBooking(0)

        assertTrue(result is ApiResult.Failure)
        assertEquals(ApiFailureKind.INVALID_RESPONSE, (result as ApiResult.Failure).kind)
    }

    private fun londonTrip() = TripRequestData(
        destination = "London, United Kingdom",
        startDate = LocalDate.of(2026, 8, 18),
        endDate = LocalDate.of(2026, 8, 27),
        budget = 3500.0,
        preferences = arrayListOf("Direct flights only"),
        notes = "Window seat",
        remoteId = 7,
        remoteTitle = "London Business Trip",
        remoteStatus = "ITINERARY_READY",
    )

    private fun itineraryItems() = listOf(
        ItineraryItem(
            time = "08:20",
            title = "Singapore to London",
            detail = "Direct flight",
            state = ItineraryItemState.PLANNED,
            type = BookingRecord.TYPE_FLIGHT,
            price = BigDecimal("412.50"),
            currency = "SGD",
            bookingRef = "AGENT-FLT-1",
        ),
        ItineraryItem(
            time = "15:00",
            title = "London business hotel",
            detail = "Nine nights",
            state = ItineraryItemState.PLANNED,
            type = BookingRecord.TYPE_HOTEL,
            price = BigDecimal("288.00"),
            currency = "SGD",
        ),
    )

    private fun bookingResponse(
        id: Long,
        type: String,
        bookingRef: String,
        price: String,
    ): MockResponse {
        return jsonResponse(
            """{"id":$id,"tripId":7,"userId":2,"type":"$type","bookingRef":"$bookingRef","price":$price,"currency":"SGD","status":"CONFIRMED"}""",
        )
    }

    private fun jsonResponse(body: String, statusCode: Int = 200): MockResponse {
        return MockResponse()
            .setResponseCode(statusCode)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
    }
}
