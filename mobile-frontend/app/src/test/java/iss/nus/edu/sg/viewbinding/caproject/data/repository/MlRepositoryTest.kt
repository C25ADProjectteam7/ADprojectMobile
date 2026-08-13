package iss.nus.edu.sg.viewbinding.caproject.data.repository

import com.google.gson.Gson
import com.google.gson.JsonParser
import iss.nus.edu.sg.viewbinding.caproject.network.ApiFailureKind
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult
import iss.nus.edu.sg.viewbinding.caproject.network.MlApi
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

class MlRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: MlRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val gson = Gson()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
        repository = MlRepository(retrofit.create(MlApi::class.java), gson)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun predictionSendsExactCamelCaseSpringContract() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{
                    "predictedPricePerNight":180.50,
                    "predictedTotalPrice":361.00,
                    "numberOfNights":2,
                    "currency":"USD",
                    "modelStatus":"READY",
                    "modelVersion":"v1",
                    "isMock":false,
                    "message":"Prediction completed"
                }""".trimIndent(),
            ),
        )

        val result = predict()
        val request = server.takeRequest()
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject

        assertTrue(result is ApiResult.Success)
        assertEquals("POST", request.method)
        assertEquals("/api/ml/predict-hotel-price", request.path)
        assertEquals("London", body.get("city").asString)
        assertEquals("2026-08-18", body.get("checkInDate").asString)
        assertEquals("2026-08-20", body.get("checkOutDate").asString)
        assertEquals("2026-08-11", body.get("bookingDate").asString)
        assertEquals(4, body.get("hotelStarRating").asInt)
        assertEquals("double", body.get("roomType").asString)
        assertEquals(1, body.get("numberOfGuests").asInt)
        assertEquals("USD", body.get("currency").asString)
        val prediction = (result as ApiResult.Success).value
        assertEquals(BigDecimal("180.50"), prediction.predictedPricePerNight)
        assertEquals(false, prediction.isMock)
    }

    @Test
    fun snakeCasePythonResponseIsAcceptedThroughCurrentSpringProxy() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{
                    "predicted_price_per_night":150,
                    "predicted_total_price":300,
                    "number_of_nights":2,
                    "currency":"USD",
                    "model_status":"MOCK_READY",
                    "model_version":"mock-v1",
                    "is_mock":true,
                    "message":"Mock prediction"
                }""".trimIndent(),
            ),
        )

        val prediction = (predict() as ApiResult.Success).value

        assertEquals(true, prediction.isMock)
        assertEquals("MOCK_READY", prediction.modelStatus)
    }

    @Test
    fun incompletePredictionIsNotReplacedWithLocalPrice() = runBlocking {
        server.enqueue(jsonResponse("""{"modelStatus":"FAILED","isMock":true}"""))

        val result = predict()

        assertEquals(ApiFailureKind.INVALID_RESPONSE, (result as ApiResult.Failure).kind)
    }

    private suspend fun predict(): ApiResult<iss.nus.edu.sg.viewbinding.caproject.model.HotelPricePrediction> {
        return repository.predictHotelPrice(
            city = "London",
            checkInDate = LocalDate.of(2026, 8, 18),
            checkOutDate = LocalDate.of(2026, 8, 20),
            hotelStarRating = 4,
            roomType = "double",
            numberOfGuests = 1,
            currency = "USD",
            bookingDate = LocalDate.of(2026, 8, 11),
        )
    }

    private fun jsonResponse(body: String): MockResponse {
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
    }
}
