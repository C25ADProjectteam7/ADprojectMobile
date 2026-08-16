package iss.nus.edu.sg.viewbinding.caproject.data.repository

import com.google.gson.Gson
import com.google.gson.JsonParser
import iss.nus.edu.sg.viewbinding.caproject.model.HotelCandidate
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * The app's half of the contextual fair-price contract: what it sends, and
 * that it displays the backend's ADJUSTED band without recomputing anything.
 */
class HotelFairPriceRepositoryTest {

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
    fun tearDown() = server.shutdown()

    private val adjustedBody = """
        {"predictionAvailable":true,"predictionSource":"ML","modelVersion":"india-v3-m2",
         "fairPriceP25":11497.85,"fairPriceP50":14576.16,"fairPriceP75":17188.98,
         "decisionLow":11497.85,"decisionHigh":17188.98,
         "rawFairPriceP25":11121.15,"rawFairPriceP50":14098.61,"rawFairPriceP75":16625.83,
         "rawDecisionLow":11121.15,"rawDecisionHigh":16625.83,
         "contextAdjustmentApplied":true,"contextAdjustmentFactor":1.033872,
         "validContextHotelCount":4,
         "currentComparablePrice":37062.87,"priceLevel":"EXPENSIVE","currency":"INR"}
    """.trimIndent()

    private suspend fun call(
        candidates: List<HotelCandidate> = listOf(
            HotelCandidate("lpA", "Hotel A"),
            HotelCandidate("lpC", "Hotel C"),
        ),
    ) = repository.predictHotelFairPrice(
        hotelId = "lpB",
        hotelName = "Hotel B",
        checkInDate = LocalDate.of(2026, 8, 22),
        candidates = candidates,
        bookingDate = LocalDate.of(2026, 8, 17),
    )

    private fun json(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    // ------------------------------------------------------------ request
    @Test
    fun sendsTheSpringV3ContractWithCandidateIdentities() = runBlocking {
        server.enqueue(json(adjustedBody))
        call()

        val request = server.takeRequest()
        // Spring proxies to the ML service; the app never calls Python directly.
        assertEquals("/api/ml/v2/hotel-price", request.path)
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals("lpB", body.get("hotelId").asString)
        assertEquals("Hotel B", body.get("hotelName").asString)
        assertEquals("2026-08-17", body.get("bookingDate").asString)
        assertEquals("2026-08-22", body.get("checkInDate").asString)

        val candidates = body.getAsJsonArray("candidateHotels")
        assertEquals(2, candidates.size())
        assertEquals("lpA", candidates[0].asJsonObject.get("hotelId").asString)
        assertEquals("Hotel A", candidates[0].asJsonObject.get("hotelName").asString)
    }

    @Test
    fun neverSendsAgentPrices() = runBlocking {
        server.enqueue(json(adjustedBody))
        call()

        val raw = server.takeRequest().body.readUtf8()
        for (banned in listOf("stayTotalPrice", "averagePricePerNight", "pricePerNight",
                              "price", "USD", "rank", "budget", "offerId", "numberOfNights")) {
            assertFalse("request must not carry $banned", raw.contains(banned))
        }
        val candidate = JsonParser.parseString(raw).asJsonObject
            .getAsJsonArray("candidateHotels")[0].asJsonObject
        assertEquals(setOf("hotelId", "hotelName"), candidate.keySet())
    }

    @Test
    fun anItineraryWithoutCandidatesStillAsksForARawPrediction() = runBlocking {
        server.enqueue(json(adjustedBody))
        call(candidates = emptyList())

        val body = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertTrue(body.has("hotelId"))
        assertEquals(0, body.getAsJsonArray("candidateHotels").size())
    }

    // ----------------------------------------------------------- response
    @Test
    fun usesAdjustedFieldsAndNeverRawOnes() = runBlocking {
        server.enqueue(json(adjustedBody))
        val outcome = (call() as ApiResult.Success).value
        val fair = (outcome as HotelFairPriceOutcome.Available).value

        // The adjusted band, verbatim.
        assertEquals(BigDecimal("14576.16"), fair.fairPriceP50)
        assertEquals(BigDecimal("11497.85"), fair.decisionLow)
        assertEquals(BigDecimal("17188.98"), fair.decisionHigh)
        // Explicitly NOT the raw band, which is also present in the payload.
        assertFalse(fair.fairPriceP50 == BigDecimal("14098.61"))
        assertFalse(fair.decisionLow == BigDecimal("11121.15"))
        assertEquals("EXPENSIVE", fair.priceLevel)
        assertEquals("INR", fair.currency)
        assertEquals(BigDecimal("37062.87"), fair.currentComparablePrice)
    }

    @Test
    fun contextFlagIsCarriedButTheFactorIsNotAppliedAgain() = runBlocking {
        server.enqueue(json(adjustedBody))
        val fair = ((call() as ApiResult.Success).value as HotelFairPriceOutcome.Available).value

        assertTrue(fair.contextAdjustmentApplied)
        // The response's factor is 1.033872. If the app re-applied it, P50
        // would be 15069.9x rather than the backend's final 14576.16.
        assertEquals(BigDecimal("14576.16"), fair.fairPriceP50)
        // And the domain model deliberately exposes no factor to re-apply.
        val fields = fair::class.java.declaredFields.map { it.name }
        assertFalse(fields.any { it.contains("actor", ignoreCase = true) })
        assertFalse(fields.any { it.startsWith("raw") })
    }

    @Test
    fun unadjustedIndiaPredictionIsStillUsable() = runBlocking {
        server.enqueue(
            json(
                """{"predictionAvailable":true,"predictionSource":"ML","modelVersion":"india-v3-m2",
                    "fairPriceP25":8000,"fairPriceP50":9000,"fairPriceP75":10000,
                    "decisionLow":8000,"decisionHigh":10350,
                    "contextAdjustmentApplied":false,"contextAdjustmentFactor":1.0,
                    "validContextHotelCount":0,
                    "currentComparablePrice":9100,"priceLevel":"FAIR","currency":"INR"}""",
            ),
        )
        val fair = ((call(emptyList()) as ApiResult.Success).value
            as HotelFairPriceOutcome.Available).value
        assertFalse(fair.contextAdjustmentApplied)
        assertEquals("FAIR", fair.priceLevel)
    }

    @Test
    fun historicalTargetRendersLikeAnyOtherVerdict() = runBlocking {
        server.enqueue(
            json(
                """{"predictionAvailable":true,"predictionSource":"HISTORICAL",
                    "modelVersion":"india-v3-m2",
                    "fairPriceP25":20000,"fairPriceP50":22643.96,"fairPriceP75":25000,
                    "decisionLow":19247.37,"decisionHigh":26040.55,
                    "contextAdjustmentApplied":false,
                    "currentComparablePrice":21375,"priceLevel":"FAIR","currency":"INR"}""",
            ),
        )
        val fair = ((call() as ApiResult.Success).value as HotelFairPriceOutcome.Available).value
        assertEquals("HISTORICAL", fair.source)
        assertFalse(fair.contextAdjustmentApplied)
        assertEquals("FAIR", fair.priceLevel)
    }

    // ---------------------------------------------------------- fallbacks
    @Test
    fun unsupportedMarketIsAnAnswerNotAFailure() = runBlocking {
        server.enqueue(json("""{"predictionAvailable":false,"reason":"UNSUPPORTED_MARKET"}"""))
        val outcome = (call() as ApiResult.Success).value
        assertEquals("UNSUPPORTED_MARKET",
                     (outcome as HotelFairPriceOutcome.Unavailable).reason)
    }

    @Test
    fun everyDocumentedUnavailableReasonIsSurfaced() = runBlocking {
        for (reason in listOf("UNSUPPORTED_MARKET", "NO_COMPARABLE_RATE",
                              "UNSUPPORTED_LEAD_TIME", "MODEL_ERROR", "INVALID_INPUT")) {
            server.enqueue(json("""{"predictionAvailable":false,"reason":"$reason"}"""))
            val outcome = (call() as ApiResult.Success).value
            assertEquals(reason, (outcome as HotelFairPriceOutcome.Unavailable).reason)
        }
    }

    @Test
    fun serviceFailureIsReportedRatherThanThrown() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(call() is ApiResult.Failure)
    }

    @Test
    fun anAvailableButIncompleteResponseIsRejected() = runBlocking {
        // predictionAvailable true with no band would otherwise render blanks.
        server.enqueue(json("""{"predictionAvailable":true,"currency":"INR"}"""))
        assertEquals(ApiFailureKind.INVALID_RESPONSE, (call() as ApiResult.Failure).kind)
    }

    @Test
    fun replacementHotelProducesAFreshRequest() = runBlocking {
        server.enqueue(json(adjustedBody))
        repository.predictHotelFairPrice(
            hotelId = "lpNEW", hotelName = "Cheaper Hotel",
            checkInDate = LocalDate.of(2026, 8, 22),
            candidates = listOf(HotelCandidate("lpD", "Hotel D"), HotelCandidate("lpE", "Hotel E")),
            bookingDate = LocalDate.of(2026, 8, 17),
        )
        val body = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals("lpNEW", body.get("hotelId").asString)
        assertEquals(listOf("lpD", "lpE"),
                     body.getAsJsonArray("candidateHotels").map { it.asJsonObject.get("hotelId").asString })
    }
}
