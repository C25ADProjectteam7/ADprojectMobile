package iss.nus.edu.sg.viewbinding.caproject.data.repository

import com.google.gson.Gson
import com.google.gson.JsonParser
import iss.nus.edu.sg.viewbinding.caproject.data.local.TripDraftDataSource
import iss.nus.edu.sg.viewbinding.caproject.model.TripRequestData
import iss.nus.edu.sg.viewbinding.caproject.network.ApiFailureKind
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult
import iss.nus.edu.sg.viewbinding.caproject.network.TripApi
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class TripRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var draftStore: FakeTripDraftStore
    private lateinit var repository: TripRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val gson = Gson()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
        draftStore = FakeTripDraftStore()
        repository = TripRepository(
            tripApi = retrofit.create(TripApi::class.java),
            gson = gson,
            draftStore = draftStore,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun createTripSendsSwaggerFieldsAndKeepsLocalNotes() = runBlocking {
        server.enqueue(tripResponse())
        val request = londonTrip()

        val result = repository.createTrip(request)
        val recordedRequest = server.takeRequest()
        val body = JsonParser.parseString(recordedRequest.body.readUtf8()).asJsonObject

        assertTrue(result is ApiResult.Success)
        assertEquals("POST", recordedRequest.method)
        assertEquals("/api/trips", recordedRequest.path)
        assertEquals("London Business Trip", body.get("title").asString)
        assertEquals("London, United Kingdom", body.get("destination").asString)
        assertEquals("2026-08-18", body.get("startDate").asString)
        assertEquals("2026-08-27", body.get("endDate").asString)
        assertEquals(BigDecimal("3500.0"), body.get("budgetTotal").asBigDecimal)
        assertEquals("Direct flights only", body.getAsJsonArray("preferences")[0].asString)
        assertFalse(body.has("notes"))
        assertEquals("Window seat", draftStore.notesFor(7))
    }

    @Test
    fun listAndGetUseExactPathsAndRestoreLocalFields() = runBlocking {
        draftStore.save(7, listOf("Business hotel"), "Late check-in")
        server.enqueue(
            jsonResponse(
                """[{"id":7,"userId":2,"title":"London Business Trip","destination":"London","startDate":"2026-08-18","endDate":"2026-08-27","budgetTotal":3500.00,"status":"DRAFT"}]""",
            ),
        )
        server.enqueue(tripResponse())

        val listResult = repository.getTrips()
        val listRequest = server.takeRequest()
        val getResult = repository.getTrip(7)
        val getRequest = server.takeRequest()

        assertEquals("GET", listRequest.method)
        assertEquals("/api/trips", listRequest.path)
        assertEquals("GET", getRequest.method)
        assertEquals("/api/trips/7", getRequest.path)
        val trip = (listResult as ApiResult.Success).value.single()
        assertEquals(LocalDate.of(2026, 8, 18), trip.startDate)
        assertEquals(arrayListOf("Business hotel"), trip.preferences)
        assertEquals("Late check-in", trip.notes)
        assertTrue(getResult is ApiResult.Success)
    }

    @Test
    fun detailMapsRemoteItineraryWithoutGeneratingMockRows() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{
                    "id":7,"title":"London Business Trip","destination":"London",
                    "startDate":"2026-08-18","endDate":"2026-08-27",
                    "budgetTotal":3500.00,"status":"ITINERARY_READY",
                    "itineraries":[{
                        "id":11,"dayNumber":1,"date":"2026-08-18","generatedByAgent":true,
                        "items":[{
                            "id":21,"type":"MEETING","startTime":"2026-08-18T15:30:00",
                            "endTime":"2026-08-18T16:30:00","title":"Client meeting",
                            "description":"{\"title\":\"Client meeting\",\"description\":\"Quarterly review\",\"startTime\":\"2026-08-18T15:30:00\"}","location":"Canary Wharf",
                            "bookingRef":null,"price":25.50,"currency":"SGD"
                        }]
                    }]
                }""".trimIndent(),
            ),
        )

        val result = repository.getTripDetail(7)
        val recordedRequest = server.takeRequest()

        assertEquals("/api/trips/7/detail", recordedRequest.path)
        val detail = (result as ApiResult.Success).value
        assertEquals("ITINERARY_READY", detail.trip.remoteStatus)
        assertEquals("Canary Wharf", detail.days.single().route)
        assertEquals("15:30", detail.days.single().items.single().time)
        assertEquals(
            "Quarterly review · Canary Wharf · SGD 25.5",
            detail.days.single().items.single().detail,
        )
    }

    @Test
    fun updateCancelAndAgentChatUseExactTripPaths() = runBlocking {
        server.enqueue(tripResponse())
        server.enqueue(jsonResponse("""{"message":"Trip cancelled"}"""))
        server.enqueue(jsonResponse("""{"taskId":"task-123","status":"PROCESSING"}""", 202))

        assertTrue(repository.updateTrip(7, londonTrip()) is ApiResult.Success)
        val updateRequest = server.takeRequest()
        assertEquals("PUT", updateRequest.method)
        assertEquals("/api/trips/7", updateRequest.path)

        assertTrue(repository.cancelTrip(7) is ApiResult.Success)
        val cancelRequest = server.takeRequest()
        assertEquals("DELETE", cancelRequest.method)
        assertEquals("/api/trips/7", cancelRequest.path)
        assertFalse(draftStore.contains(7))

        val chatResult = repository.startAgentChat(7, "Move the meeting to 3 PM")
        val chatRequest = server.takeRequest()
        val chatBody = JsonParser.parseString(chatRequest.body.readUtf8()).asJsonObject
        assertTrue(chatResult is ApiResult.Success)
        assertEquals("POST", chatRequest.method)
        assertEquals("/api/trips/7/agent-chat", chatRequest.path)
        assertEquals("Move the meeting to 3 PM", chatBody.get("message").asString)
        assertEquals("task-123", (chatResult as ApiResult.Success).value.taskId)
    }

    @Test
    fun malformedBackendDateReturnsInvalidResponse() = runBlocking {
        server.enqueue(
            jsonResponse(
                """[{"id":7,"userId":2,"title":"London","destination":"London","startDate":"18-08-2026","endDate":"2026-08-27","budgetTotal":3500,"status":"DRAFT"}]""",
            ),
        )

        val result = repository.getTrips()

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
    )

    private fun tripResponse(): MockResponse {
        return jsonResponse(
            """{"id":7,"userId":2,"title":"London Business Trip","destination":"London, United Kingdom","startDate":"2026-08-18","endDate":"2026-08-27","budgetTotal":3500.00,"status":"DRAFT"}""",
        )
    }

    private fun jsonResponse(body: String, statusCode: Int = 200): MockResponse {
        return MockResponse()
            .setResponseCode(statusCode)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
    }

    private class FakeTripDraftStore : TripDraftDataSource {
        private val values = mutableMapOf<Long, Pair<ArrayList<String>, String>>()

        override fun save(tripId: Long, tripPreferences: List<String>, notes: String) {
            values[tripId] = ArrayList(tripPreferences) to notes
        }

        override fun preferencesFor(tripId: Long): ArrayList<String> {
            return ArrayList(values[tripId]?.first.orEmpty())
        }

        override fun notesFor(tripId: Long): String = values[tripId]?.second.orEmpty()

        override fun remove(tripId: Long) {
            values.remove(tripId)
        }

        fun contains(tripId: Long): Boolean = values.containsKey(tripId)
    }
}
