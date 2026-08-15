package iss.nus.edu.sg.viewbinding.caproject.network

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import iss.nus.edu.sg.viewbinding.caproject.network.model.agent.AgentTaskResponse
import iss.nus.edu.sg.viewbinding.caproject.network.model.common.ApiResponse
import iss.nus.edu.sg.viewbinding.caproject.network.model.trip.TripResponse
import iss.nus.edu.sg.viewbinding.caproject.network.model.user.UserResponse
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT

class ApiContractTest {

    private val gson = Gson()

    @Test
    fun allApiContractsExposeExpectedMethodsAndPaths() {
        val endpoints = listOf(
            AuthApi::class.java,
            TripApi::class.java,
            AgentApi::class.java,
            BookingApi::class.java,
            ExpenseApi::class.java,
            UserApi::class.java,
            MlApi::class.java,
        ).flatMap(::endpointsFor).toSet()

        assertEquals(EXPECTED_ENDPOINTS, endpoints)
        assertEquals(28, endpoints.size)
    }

    @Test
    fun directDtoResponseParsesWithoutWrapper() {
        val response = gson.fromJson(
            """{
                "id":7,"userId":2,"title":"London Business Trip",
                "destination":"London","startDate":"2026-08-18",
                "endDate":"2026-08-27","budgetTotal":3500.00,"status":"DRAFT"
            }""".trimIndent(),
            TripResponse::class.java,
        )

        assertEquals(7L, response.id)
        assertEquals("London", response.destination)
        assertEquals(BigDecimal("3500.00"), response.budgetTotal)
    }

    @Test
    fun wrappedResponseParsesDataAndMessage() {
        val responseType = TypeToken.getParameterized(
            ApiResponse::class.java,
            UserResponse::class.java,
        ).type
        val response: ApiResponse<UserResponse> = gson.fromJson(
            """{
                "code":200,"message":"success","data":{
                    "id":2,"username":"ashley.tan","email":null,
                    "department":"Sales","phone":null,"role":"EMPLOYEE"
                }
            }""".trimIndent(),
            responseType,
        )

        assertEquals(200, response.code)
        assertEquals("ashley.tan", response.data?.username)
        assertEquals("Sales", response.data?.department)
    }

    @Test
    fun dynamicAgentTaskPreservesResultJson() {
        val response = gson.fromJson(
            """{
                "status":"DONE",
                "result":{"status":"ITINERARY_READY","itinerary":{"day1":{"date":"2026-08-18"}}}
            }""".trimIndent(),
            AgentTaskResponse::class.java,
        )

        assertEquals("DONE", response.status)
        val itinerary = response.result?.getAsJsonObject("itinerary")
        assertNotNull(itinerary)
        assertEquals(
            "2026-08-18",
            itinerary?.getAsJsonObject("day1")?.get("date")?.asString,
        )
        assertEquals(
            "ITINERARY_READY",
            JsonParser.parseString(response.result.toString())
                .asJsonObject.get("status").asString,
        )
    }

    private fun endpointsFor(apiClass: Class<*>): List<String> {
        return apiClass.declaredMethods.mapNotNull { method ->
            method.getAnnotation(GET::class.java)?.let { "GET ${it.value}" }
                ?: method.getAnnotation(POST::class.java)?.let { "POST ${it.value}" }
                ?: method.getAnnotation(PUT::class.java)?.let { "PUT ${it.value}" }
                ?: method.getAnnotation(DELETE::class.java)?.let { "DELETE ${it.value}" }
        }
    }

    private companion object {
        val EXPECTED_ENDPOINTS = setOf(
            "POST api/auth/login",
            "POST api/auth/register",
            "POST api/auth/forgot-password",
            "GET api/trips",
            "POST api/trips",
            "GET api/trips/{id}",
            "PUT api/trips/{id}",
            "DELETE api/trips/{id}",
            "GET api/trips/{id}/detail",
            "POST api/trips/{id}/agent-chat",
            "POST api/trips/{id}/agent-modify",
            "POST api/agent/extract-requirements",
            "POST api/agent/generate-itinerary",
            "POST api/agent/modify-itinerary",
            "GET api/agent/tasks/{taskId}",
            "GET api/bookings",
            "GET api/bookings/{id}",
            "POST api/bookings/{tripId}",
            "PUT api/bookings/{id}/cancel",
            "GET api/expenses",
            "GET api/expenses/{id}",
            "POST api/expenses/{tripId}",
            "POST api/expenses/upload-receipt",
            "GET api/users/me",
            "PUT api/users/me",
            "PUT api/users/me/password",
            "POST api/ml/predict-hotel-price",
            "POST api/ml/v2/price-advice",
        )
    }
}
