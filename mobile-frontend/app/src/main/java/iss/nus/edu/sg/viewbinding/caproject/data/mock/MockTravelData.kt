package iss.nus.edu.sg.viewbinding.caproject.data.mock

import iss.nus.edu.sg.viewbinding.caproject.model.DailyItinerary
import iss.nus.edu.sg.viewbinding.caproject.model.ItineraryReview
import iss.nus.edu.sg.viewbinding.caproject.model.ItineraryItem
import iss.nus.edu.sg.viewbinding.caproject.model.ItineraryItemState
import iss.nus.edu.sg.viewbinding.caproject.model.MockBookingConfirmation
import iss.nus.edu.sg.viewbinding.caproject.model.TripRequestData
import iss.nus.edu.sg.viewbinding.caproject.model.TripSummary
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale

object MockTravelData {

    fun tripSummaryFor(tripRequest: TripRequestData, isMockBooked: Boolean): TripSummary {
        return TripSummary(
            title = "${tripRequest.city} Business Trip",
            dates = "${formatDateRange(tripRequest)}  ·  ${tripRequest.tripDays} days",
            route = "Singapore → ${tripRequest.city}",
            budgetSummary = "Estimated S$700 of ${formatSgd(tripRequest.budget)} budget",
            status = if (isMockBooked) "Mock booked" else "Itinerary ready",
        )
    }

    fun itineraryFor(tripRequest: TripRequestData): ItineraryReview = ItineraryReview(
        flightRoute = "Singapore → ${tripRequest.city}",
        flightSummary = "Demo flight · Direct · 1h 45m",
        flightSchedule = "${tripRequest.startDate.format(DAY_MONTH)} · 08:20 Singapore → 10:05 ${tripRequest.city}",
        flightNumber = "DEMO101",
        flightPrice = 412.0,
        hotelName = "${tripRequest.city} Central Hotel",
        hotelSummary = "Demo hotel near city centre · ★4.3",
        hotelPrediction = "Mock price prediction · likely +8% in 3 days",
        hotelStay = "${formatDateRange(tripRequest, includeYear = false)} · ${tripRequest.tripDays - 1} nights",
        hotelRate = "S$96/night",
        hotelPrice = 288.0,
        transferTitle = "${tripRequest.city} airport → city centre",
        transferDescription = "Mock ground-transfer estimate",
        transferDuration = "45 min",
        estimatedTotal = 700.0,
    )

    fun bookingFor(tripRequest: TripRequestData): MockBookingConfirmation {
        val destinationCode = tripRequest.city
            .filter(Char::isLetter)
            .take(3)
            .uppercase(Locale.ENGLISH)
            .ifBlank { "TRP" }
        return MockBookingConfirmation(
            reference = "MOCK-$destinationCode-${tripRequest.startDate.dayOfMonth.toString().padStart(2, '0')}${tripRequest.endDate.dayOfMonth.toString().padStart(2, '0')}",
            dateRange = formatDateRange(tripRequest),
            flightDetails = "DEMO101 · ${tripRequest.startDate.format(DAY_MONTH)} · 08:20",
            flightTerminal = "Terminal 3 · Direct · 1h 45m",
            hotelRoom = "Business room · Breakfast included",
            hotelConfirmationCode = "${destinationCode}H288",
        )
    }

    fun dailyItineraryFor(tripRequest: TripRequestData): List<DailyItinerary> {
        return (0 until tripRequest.tripDays).map { dayIndex ->
            val date = tripRequest.startDate.plusDays(dayIndex.toLong())
            val isFirstDay = dayIndex == 0
            val isLastDay = dayIndex == tripRequest.tripDays - 1
            DailyItinerary(
                dayNumber = dayIndex + 1,
                date = date,
                route = when {
                    isFirstDay -> "Singapore → ${tripRequest.city}"
                    isLastDay && dayIndex > 0 -> "${tripRequest.city} → Singapore"
                    else -> "Business day in ${tripRequest.city}"
                },
                items = when {
                    isFirstDay -> arrivalDayItems(tripRequest)
                    isLastDay -> departureDayItems(tripRequest)
                    else -> businessDayItems(tripRequest, dayIndex + 1)
                },
            )
        }
    }

    private fun arrivalDayItems(tripRequest: TripRequestData): List<ItineraryItem> = listOf(
        ItineraryItem(
            time = "08:20",
            title = "Demo flight to ${tripRequest.city}",
            detail = "Singapore T3 → ${tripRequest.city} · Mock confirmed",
            state = ItineraryItemState.CONFIRMED,
        ),
        ItineraryItem(
            time = "10:45",
            title = "Transfer to city centre",
            detail = "Estimated travel time: 45 minutes",
            state = ItineraryItemState.UPCOMING,
        ),
        ItineraryItem(
            time = "12:00",
            title = "Hotel check-in",
            detail = "${tripRequest.city} Central Hotel",
            state = ItineraryItemState.PLANNED,
        ),
        ItineraryItem(
            time = "14:30",
            title = "Client meeting",
            detail = "Business district · ${tripRequest.city}",
            state = ItineraryItemState.PLANNED,
        ),
    )

    private fun businessDayItems(
        tripRequest: TripRequestData,
        dayNumber: Int,
    ): List<ItineraryItem> = listOf(
        ItineraryItem("08:00", "Breakfast", "${tripRequest.city} Central Hotel", ItineraryItemState.CONFIRMED),
        ItineraryItem("09:30", "Project workshop", "Client office · Day $dayNumber", ItineraryItemState.UPCOMING),
        ItineraryItem("12:30", "Team lunch", "Near the client office", ItineraryItemState.PLANNED),
        ItineraryItem("15:00", "Follow-up meeting", "Business district · ${tripRequest.city}", ItineraryItemState.PLANNED),
    )

    private fun departureDayItems(tripRequest: TripRequestData): List<ItineraryItem> = listOf(
        ItineraryItem("08:00", "Breakfast", "${tripRequest.city} Central Hotel", ItineraryItemState.CONFIRMED),
        ItineraryItem("10:00", "Hotel check-out", "Business room · Mock booking", ItineraryItemState.UPCOMING),
        ItineraryItem("12:30", "Transfer to airport", "Estimated travel time: 45 minutes", ItineraryItemState.PLANNED),
        ItineraryItem("16:20", "Demo flight to Singapore", "${tripRequest.city} → Singapore · Mock confirmed", ItineraryItemState.PLANNED),
    )

    private fun formatDateRange(
        tripRequest: TripRequestData,
        includeYear: Boolean = true,
    ): String {
        val startDate = tripRequest.startDate
        val endDate = tripRequest.endDate
        if (startDate.month == endDate.month && startDate.year == endDate.year) {
            val suffix = endDate.format(
                if (includeYear) MONTH_YEAR else MONTH,
            )
            return "${startDate.dayOfMonth}–${endDate.dayOfMonth} $suffix"
        }
        val formatter = if (includeYear) FULL_DATE else DAY_MONTH
        return "${startDate.format(formatter)}–${endDate.format(formatter)}"
    }

    private fun formatSgd(value: Double): String {
        val formatter = NumberFormat.getNumberInstance(Locale.ENGLISH).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 2
        }
        return "S$${formatter.format(value)}"
    }

    private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val MONTH = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)
    private val MONTH_YEAR = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)
    private val FULL_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
}
