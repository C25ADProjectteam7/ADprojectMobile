package iss.nus.edu.sg.viewbinding.caproject.data.mock

import iss.nus.edu.sg.viewbinding.caproject.model.TripRequestData
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockTravelDataTest {

    @Test
    fun londonRequest_populatesEveryDownstreamSummary() {
        val request = TripRequestData(
            destination = "London, United Kingdom",
            startDate = LocalDate.of(2026, 9, 4),
            endDate = LocalDate.of(2026, 9, 9),
            budget = 3_500.0,
            preferences = arrayListOf("Direct flights only"),
            notes = "Window seat",
        )

        val itinerary = MockTravelData.itineraryFor(request)
        val booking = MockTravelData.bookingFor(request)
        val summary = MockTravelData.tripSummaryFor(request, isMockBooked = true)

        assertEquals("Singapore → London", itinerary.flightRoute)
        assertEquals("London Central Hotel", itinerary.hotelName)
        assertTrue(itinerary.flightSchedule.contains("4 Sep"))
        assertEquals("London Business Trip", summary.title)
        assertTrue(summary.dates.contains("4–9 Sep 2026"))
        assertTrue(summary.budgetSummary.contains("S$3,500"))
        assertEquals("4–9 Sep 2026", booking.dateRange)
        assertTrue(booking.reference.startsWith("MOCK-LON-"))
    }

    @Test
    fun tokyoRequest_doesNotReuseLondonOrJakartaContent() {
        val request = TripRequestData(
            destination = "Tokyo, Japan",
            startDate = LocalDate.of(2026, 10, 20),
            endDate = LocalDate.of(2026, 10, 22),
            budget = 1_800.0,
            preferences = arrayListOf(),
            notes = "",
        )

        val itinerary = MockTravelData.itineraryFor(request)
        val booking = MockTravelData.bookingFor(request)

        assertEquals("Singapore → Tokyo", itinerary.flightRoute)
        assertEquals("Tokyo Central Hotel", itinerary.hotelName)
        assertEquals("20–22 Oct 2026", booking.dateRange)
        assertTrue(booking.reference.startsWith("MOCK-TOK-"))
    }

    @Test
    fun dailyItinerary_usesSubmittedDestinationDatesAndDayCount() {
        val request = TripRequestData(
            destination = "London, United Kingdom",
            startDate = LocalDate.of(2026, 9, 4),
            endDate = LocalDate.of(2026, 9, 6),
            budget = 3_500.0,
            preferences = arrayListOf(),
            notes = "",
        )

        val days = MockTravelData.dailyItineraryFor(request)

        assertEquals(3, days.size)
        assertEquals(LocalDate.of(2026, 9, 4), days.first().date)
        assertEquals("Singapore → London", days.first().route)
        assertTrue(days.first().items.first().title.contains("London"))
        assertEquals("London → Singapore", days.last().route)
        assertTrue(days.last().items.last().detail.contains("London"))
    }
}
