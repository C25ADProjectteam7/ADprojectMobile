package iss.nus.edu.sg.viewbinding.caproject.ui.trips

import iss.nus.edu.sg.viewbinding.caproject.model.TripRequestData
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object TripUiFormatter {

    fun dates(trip: TripRequestData): String {
        val dateRange = if (
            trip.startDate.month == trip.endDate.month && trip.startDate.year == trip.endDate.year
        ) {
            "${trip.startDate.dayOfMonth}–${trip.endDate.dayOfMonth} ${trip.endDate.format(MONTH_YEAR)}"
        } else {
            "${trip.startDate.format(FULL_DATE)}–${trip.endDate.format(FULL_DATE)}"
        }
        return "$dateRange  ·  ${trip.tripDays} days"
    }

    fun route(trip: TripRequestData): String = "Singapore → ${trip.city}"

    fun budget(trip: TripRequestData): String = "Budget ${formatSgd(trip.budget)}"

    fun status(trip: TripRequestData): String {
        return trip.remoteStatus
            .replace('_', ' ')
            .lowercase(Locale.ENGLISH)
            .replaceFirstChar { it.titlecase(Locale.ENGLISH) }
            .ifBlank { "Created" }
    }

    private fun formatSgd(value: Double): String {
        val formatter = NumberFormat.getNumberInstance(Locale.ENGLISH).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 2
        }
        return "S$${formatter.format(value)}"
    }

    private val MONTH_YEAR = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)
    private val FULL_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
}
