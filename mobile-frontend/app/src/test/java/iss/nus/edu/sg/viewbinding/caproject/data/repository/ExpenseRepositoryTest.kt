package iss.nus.edu.sg.viewbinding.caproject.data.repository

import com.google.gson.Gson
import iss.nus.edu.sg.viewbinding.caproject.model.ReceiptUpload
import iss.nus.edu.sg.viewbinding.caproject.network.ApiFailureKind
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult
import iss.nus.edu.sg.viewbinding.caproject.network.ExpenseApi
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

class ExpenseRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: ExpenseRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val gson = Gson()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
        repository = ExpenseRepository(retrofit.create(ExpenseApi::class.java), gson)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun uploadReceiptUsesMultipartEndpointAndReturnedExpenseOnlyOnce() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"code":200,"message":"Receipt uploaded","data":${expenseJson(21)}}""",
            ),
        )

        val result = repository.uploadReceipt(
            tripId = 7,
            category = "MEAL",
            amount = BigDecimal("68.50"),
            currency = "SGD",
            description = ExpenseDescriptionCodec.encode(
                "Blue Jasmine",
                LocalDate.of(2026, 8, 13),
                "Client dinner",
            ),
            receipt = ReceiptUpload("receipt.png", "image/png", byteArrayOf(1, 2, 3)),
        )

        assertTrue(result is ApiResult.Success)
        assertEquals(21L, (result as ApiResult.Success).value.id)
        assertEquals("Blue Jasmine", result.value.merchant)
        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path.orEmpty().startsWith("/api/expenses/upload-receipt?"))
        assertTrue(request.path.orEmpty().contains("tripId=7"))
        assertTrue(request.path.orEmpty().contains("category=MEAL"))
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("multipart/form-data"))
        assertTrue(request.body.readUtf8().contains("receipt.png"))
    }

    @Test
    fun listAndDetailUseExactSwaggerPathsAndMapStatus() = runBlocking {
        server.enqueue(jsonResponse("[${expenseJson(22)},${expenseJson(21)}]"))
        server.enqueue(jsonResponse(expenseJson(21)))

        val list = repository.getExpenses()
        val detail = repository.getExpense(21)

        assertEquals("/api/expenses", server.takeRequest().path)
        assertEquals("/api/expenses/21", server.takeRequest().path)
        assertEquals(listOf(22L, 21L), (list as ApiResult.Success).value.map { it.id })
        assertEquals("SUBMITTED", (detail as ApiResult.Success).value.status)
    }

    @Test
    fun malformedExpenseDoesNotBecomeMockData() = runBlocking {
        server.enqueue(jsonResponse(expenseJson(21).replace("\"MEAL\"", "\"UNKNOWN\"")))

        val result = repository.getExpense(21)

        assertEquals(ApiFailureKind.INVALID_RESPONSE, (result as ApiResult.Failure).kind)
    }

    private fun expenseJson(id: Long): String {
        return """{
            "id":$id,
            "tripId":7,
            "userId":2,
            "category":"MEAL",
            "amount":68.50,
            "currency":"SGD",
            "description":"Merchant: Blue Jasmine\nExpense date: 2026-08-13\nNotes: Client dinner",
            "receiptUrl":"/uploads/receipt.png",
            "status":"SUBMITTED",
            "submittedAt":"2026-08-14T17:42:00"
        }""".trimIndent()
    }

    private fun jsonResponse(body: String): MockResponse {
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
    }
}
