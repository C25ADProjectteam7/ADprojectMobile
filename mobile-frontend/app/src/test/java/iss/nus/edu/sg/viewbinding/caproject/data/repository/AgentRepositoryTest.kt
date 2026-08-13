package iss.nus.edu.sg.viewbinding.caproject.data.repository

import com.google.gson.Gson
import iss.nus.edu.sg.viewbinding.caproject.network.AgentApi
import iss.nus.edu.sg.viewbinding.caproject.network.ApiFailureKind
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

class AgentRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: AgentRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val gson = Gson()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
        repository = AgentRepository(
            agentApi = retrofit.create(AgentApi::class.java),
            gson = gson,
            pollingIntervalMillis = 0,
            maxPollingAttempts = 3,
            waitBeforeNextPoll = {},
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun processingTaskPollsUntilItineraryIsReady() = runBlocking {
        server.enqueue(jsonResponse("""{"status":"PROCESSING","tripId":7}"""))
        server.enqueue(
            jsonResponse(
                """{"status":"DONE","result":{"status":"ITINERARY_READY","itinerary":{}}}""",
            ),
        )
        val statuses = mutableListOf<String>()

        val result = repository.awaitTask("task-123") { statuses += it.status }

        assertEquals(AgentPollResult.ItineraryReady, result)
        assertEquals(listOf("PROCESSING"), statuses)
        assertEquals("/api/agent/tasks/task-123", server.takeRequest().path)
        assertEquals("/api/agent/tasks/task-123", server.takeRequest().path)
    }

    @Test
    fun doneTaskCanRequestMissingInformation() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{
                    "status":"DONE",
                    "result":{
                        "status":"NEEDS_MORE_INFO",
                        "missingFields":["destination","budgetTotal"],
                        "clarifyingQuestion":"Where are you travelling and what is the budget?"
                    }
                }""".trimIndent(),
            ),
        )

        val result = repository.awaitTask("task-needs-info")

        assertTrue(result is AgentPollResult.NeedsMoreInfo)
        result as AgentPollResult.NeedsMoreInfo
        assertEquals(listOf("destination", "budgetTotal"), result.missingFields)
        assertEquals("Where are you travelling and what is the budget?", result.clarifyingQuestion)
    }

    @Test
    fun failedAndUnknownTasksReturnHonestTerminalStates() = runBlocking {
        server.enqueue(jsonResponse("""{"status":"FAILED","error":"Agent service unavailable"}"""))
        server.enqueue(jsonResponse("""{"status":"MYSTERY"}"""))

        val failed = repository.awaitTask("task-failed")
        val invalid = repository.awaitTask("task-invalid")

        assertEquals(AgentPollResult.TaskFailed("Agent service unavailable"), failed)
        assertEquals(AgentPollResult.InvalidResponse, invalid)
    }

    @Test
    fun processingTaskTimesOutWithoutClaimingSuccess() = runBlocking {
        repeat(3) { server.enqueue(jsonResponse("""{"status":"PROCESSING"}""")) }

        val result = repository.awaitTask("task-slow")

        assertEquals(AgentPollResult.TimedOut, result)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun backendFailureKeepsFailureClassification() = runBlocking {
        server.enqueue(jsonResponse("""{"code":404,"message":"Unknown task"}""", 404))

        val result = repository.awaitTask("missing-task")

        assertTrue(result is AgentPollResult.RequestFailure)
        assertEquals(
            ApiFailureKind.NOT_FOUND,
            (result as AgentPollResult.RequestFailure).failure.kind,
        )
    }

    private fun jsonResponse(body: String, statusCode: Int = 200): MockResponse {
        return MockResponse()
            .setResponseCode(statusCode)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
    }
}
