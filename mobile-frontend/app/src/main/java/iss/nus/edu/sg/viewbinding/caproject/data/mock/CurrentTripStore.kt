package iss.nus.edu.sg.viewbinding.caproject.data.mock

import iss.nus.edu.sg.viewbinding.caproject.model.TripRequestData
import java.time.LocalDate

object CurrentTripStore {

    var currentTrip: TripRequestData = TripRequestData(
        destination = "Jakarta, Indonesia",
        startDate = LocalDate.of(2026, 8, 12),
        endDate = LocalDate.of(2026, 8, 14),
        budget = 2_000.0,
        preferences = arrayListOf("Near city centre", "Direct flights only"),
        notes = "",
    )
        private set

    var isMockBooked: Boolean = true
        private set

    fun saveRequest(tripRequest: TripRequestData) {
        currentTrip = tripRequest
        isMockBooked = false
    }

    fun confirmMockBooking(tripRequest: TripRequestData) {
        currentTrip = tripRequest
        isMockBooked = true
    }
}
