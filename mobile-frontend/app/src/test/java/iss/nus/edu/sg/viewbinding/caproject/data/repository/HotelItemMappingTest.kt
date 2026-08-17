package iss.nus.edu.sg.viewbinding.caproject.data.repository

import iss.nus.edu.sg.viewbinding.caproject.network.model.trip.ItineraryItemResponse
import iss.nus.edu.sg.viewbinding.caproject.network.model.trip.ItineraryResponse
import iss.nus.edu.sg.viewbinding.caproject.network.model.trip.TripDetailResponse
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Agent's hotel JSON has to survive the trip-detail mapping intact, because
 * the fair-price flow reads hotelId/candidateHotels out of it structurally.
 *
 * This also pins that carrying the raw JSON did not disturb the PR #10 price
 * semantics: the hotel item's price stays the whole-stay total in its own
 * currency, which is a different figure from the fair-price benchmark.
 */
class HotelItemMappingTest {

    private val hotelJson = """
        {"name":"Hotel B","hotelId":"lpB","address":"Marine Drive",
         "stayTotalPrice":70.59,"averagePricePerNight":35.30,"numberOfNights":2,
         "currency":"USD","offerId":"offer-B",
         "candidateHotels":[{"hotelId":"lpA","hotelName":"Hotel A"},
                            {"hotelId":"lpC","hotelName":"Hotel C"}]}
    """.trimIndent()

    private fun detail(description: String?, price: BigDecimal? = BigDecimal("70.59")) =
        TripDetailResponse(
            id = 1, title = "Mumbai trip", destination = "Mumbai",
            startDate = "2026-08-22", endDate = "2026-08-24",
            budgetTotal = BigDecimal("3500.00"), status = "DRAFT",
            itineraries = listOf(
                ItineraryResponse(
                    id = 1, dayNumber = 1, date = "2026-08-22", generatedByAgent = true,
                    items = listOf(
                        ItineraryItemResponse(
                            id = 10, type = "HOTEL", startTime = null, endTime = null,
                            title = "Hotel B", description = description,
                            location = "Marine Drive", bookingRef = null,
                            price = price, currency = "USD",
                        ),
                    ),
                ),
            ),
        ).toTripDetailData()

    private fun hotelItem(description: String?) =
        detail(description).days.first().items.first()

    @Test
    fun rawAgentJsonSurvivesTheMapping() {
        val item = hotelItem(hotelJson)
        assertEquals(hotelJson, item.rawJson)
        assertEquals("lpB", HotelCandidateContext.hotelId(item))
        assertEquals(listOf("lpA", "lpC"),
                     HotelCandidateContext.candidates(item).map { it.hotelId })
    }

    @Test
    fun displayDetailRemainsFlattenedAndLossy() {
        // detail is for humans; it is NOT where identity comes from.
        val item = hotelItem(hotelJson)
        assertNotNull(item.detail)
        assertFalse(item.detail.contains("candidateHotels"))
    }

    @Test
    fun pr10StayTotalSemanticsAreUnchanged() {
        val item = hotelItem(hotelJson)
        // The card renders item.price with the "%s total stay" label - it is
        // the whole-stay booking quote, never a nightly rate and never the
        // fair-price benchmark.
        assertEquals(BigDecimal("70.59"), item.price)
        assertEquals("USD", item.currency)
        assertEquals("HOTEL", item.type)
    }

    @Test
    fun preCandidateItineraryStillMapsAndFallsBack() {
        val legacy = """{"name":"Hotel B","pricePerNight":70.59,"offerId":"o"}"""
        val item = hotelItem(legacy)
        assertEquals(BigDecimal("70.59"), item.price)
        assertEquals(null, HotelCandidateContext.hotelId(item))
        assertTrue(HotelCandidateContext.candidates(item).isEmpty())
    }

    @Test
    fun anItemWithNoDescriptionIsStillUsable() {
        val item = hotelItem(null)
        assertEquals(null, item.rawJson)
        assertEquals(BigDecimal("70.59"), item.price)
        assertEquals(null, HotelCandidateContext.hotelId(item))
    }
}
