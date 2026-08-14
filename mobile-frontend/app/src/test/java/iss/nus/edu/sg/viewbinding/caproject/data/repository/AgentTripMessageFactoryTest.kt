package iss.nus.edu.sg.viewbinding.caproject.data.repository

import iss.nus.edu.sg.viewbinding.caproject.model.TripRequestData
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTripMessageFactoryTest {

    private val trip = TripRequestData(
        destination = "London, United Kingdom",
        startDate = LocalDate.of(2026, 8, 18),
        endDate = LocalDate.of(2026, 8, 27),
        budget = 3500.0,
        preferences = arrayListOf("Direct flights only", "Business hotel"),
        notes = "Window seat",
        remoteId = 7,
    )

    @Test
    fun initialRequestIncludesEveryRequirementNeededByAgentExtraction() {
        val message = AgentTripMessageFactory.initialRequest(trip)

        assertTrue(message.contains("from Singapore to London, United Kingdom"))
        assertTrue(message.contains("from 2026-08-18 to 2026-08-27"))
        assertTrue(message.contains("Total budget SGD 3500"))
        assertTrue(message.contains("Direct flights only, Business hotel"))
        assertTrue(message.contains("Notes: Window seat"))
        assertTrue(message.endsWith("Create a complete day-by-day itinerary."))
    }

    @Test
    fun modificationKeepsTripFactsAndAddsRequestedChange() {
        val message = AgentTripMessageFactory.modificationRequest(
            trip,
            "Move the client meeting to 3 PM",
        )

        assertTrue(message.contains("London, United Kingdom"))
        assertTrue(message.contains("SGD 3500"))
        assertTrue(message.contains("regenerate the itinerary"))
        assertTrue(message.endsWith("Move the client meeting to 3 PM"))
    }
}
