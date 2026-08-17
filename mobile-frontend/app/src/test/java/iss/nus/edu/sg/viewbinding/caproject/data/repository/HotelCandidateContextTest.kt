package iss.nus.edu.sg.viewbinding.caproject.data.repository

import iss.nus.edu.sg.viewbinding.caproject.model.ItineraryItem
import iss.nus.edu.sg.viewbinding.caproject.model.ItineraryItemState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hotel identity and candidate set are read as STRUCTURED JSON from the
 * Agent's activity payload - never by parsing the card's display text back
 * apart, which is lossy.
 */
class HotelCandidateContextTest {

    private fun hotelItem(rawJson: String?): ItineraryItem = ItineraryItem(
        time = "Check-in 14:00",
        title = "Hotel B",
        detail = "some · flattened · display · text",
        state = ItineraryItemState.PLANNED,
        type = "HOTEL",
        rawJson = rawJson,
    )

    private val agentJson = """
        {"name":"Hotel B","hotelId":"lpB","address":"addr",
         "stayTotalPrice":70.59,"averagePricePerNight":35.30,"numberOfNights":2,
         "currency":"USD","offerId":"offer-B",
         "candidateHotels":[
           {"hotelId":"lpA","hotelName":"Hotel A"},
           {"hotelId":"lpB","hotelName":"Hotel B"},
           {"hotelId":"lpC","hotelName":"Hotel C"}]}
    """.trimIndent()

    @Test
    fun readsHotelIdFromStructuredJson() {
        assertEquals("lpB", HotelCandidateContext.hotelId(hotelItem(agentJson)))
    }

    @Test
    fun readsCandidatesInAgentOrder() {
        val candidates = HotelCandidateContext.candidates(hotelItem(agentJson))
        assertEquals(listOf("lpA", "lpB", "lpC"), candidates.map { it.hotelId })
        assertEquals(listOf("Hotel A", "Hotel B", "Hotel C"), candidates.map { it.hotelName })
    }

    @Test
    fun candidatesCarryIdentityOnly() {
        // The domain type has exactly two properties, so no Agent price can
        // ride along even if the JSON contains one.
        val candidate = HotelCandidateContext.candidates(hotelItem(agentJson)).first()
        val names = candidate::class.java.declaredFields.map { it.name }.toSet()
        assertEquals(setOf("hotelId", "hotelName"), names)
    }

    @Test
    fun preCandidateItineraryYieldsNothing() {
        val legacy = """{"name":"Hotel B","pricePerNight":70.59,"offerId":"o"}"""
        assertNull(HotelCandidateContext.hotelId(hotelItem(legacy)))
        assertTrue(HotelCandidateContext.candidates(hotelItem(legacy)).isEmpty())
    }

    @Test
    fun missingOrMalformedJsonIsTolerated() {
        for (raw in listOf(null, "", "   ", "not json", "[1,2,3]", "{oops")) {
            assertNull(HotelCandidateContext.hotelId(hotelItem(raw)))
            assertTrue(HotelCandidateContext.candidates(hotelItem(raw)).isEmpty())
        }
    }

    @Test
    fun malformedCandidateEntriesAreSkipped() {
        val json = """
            {"hotelId":"lpB","candidateHotels":[
              {"hotelId":"lpA","hotelName":"Hotel A"},
              {"hotelName":"no id"},
              "not-an-object",
              {"hotelId":"  "},
              {"hotelId":"lpC"}]}
        """.trimIndent()
        val candidates = HotelCandidateContext.candidates(hotelItem(json))
        assertEquals(listOf("lpA", "lpC"), candidates.map { it.hotelId })
        assertNull(candidates.last().hotelName)
    }

    @Test
    fun displayTextIsNeverParsedForIdentity() {
        // Same display text, no rawJson -> nothing is recovered.
        val item = ItineraryItem(
            time = "Check-in 14:00",
            title = "Hotel B",
            detail = "hotelId lpB · candidateHotels lpA, lpC",
            state = ItineraryItemState.PLANNED,
            type = "HOTEL",
            rawJson = null,
        )
        assertNull(HotelCandidateContext.hotelId(item))
        assertTrue(HotelCandidateContext.candidates(item).isEmpty())
    }
}
