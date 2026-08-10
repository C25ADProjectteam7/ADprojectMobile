package iss.nus.edu.sg.viewbinding.caproject.model

import java.io.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class TripRequestData(
    val destination: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val budget: Double,
    val preferences: ArrayList<String>,
    val notes: String,
) : Serializable {

    val city: String
        get() = destination.substringBefore(",").trim().ifBlank { destination.trim() }

    val tripDays: Int
        get() = ChronoUnit.DAYS.between(startDate, endDate).toInt() + 1

    companion object {
        const val EXTRA_KEY = "trip_request_data"
    }
}
