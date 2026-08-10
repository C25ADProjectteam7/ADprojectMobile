package iss.nus.edu.sg.viewbinding.caproject.model

import java.time.LocalDate

data class DailyItinerary(
    val dayNumber: Int,
    val date: LocalDate,
    val route: String,
    val items: List<ItineraryItem>,
)

data class ItineraryItem(
    val time: String,
    val title: String,
    val detail: String,
    val state: ItineraryItemState,
)

enum class ItineraryItemState {
    CONFIRMED,
    UPCOMING,
    PLANNED,
}
