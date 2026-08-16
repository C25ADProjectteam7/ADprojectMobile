package iss.nus.edu.sg.viewbinding.caproject.model

import java.math.BigDecimal
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
    val type: String = "",
    val endTime: String? = null,
    val price: BigDecimal? = null,
    val currency: String? = null,
    val location: String? = null,
    val bookingRef: String? = null,
    // The Agent's activity JSON exactly as the backend stored it. `detail`
    // above is a human-readable flattening of the same data and is lossy, so
    // anything that needs a specific field (hotelId, candidateHotels) must
    // read it from here rather than parsing display text back apart.
    val rawJson: String? = null,
)

enum class ItineraryItemState {
    CONFIRMED,
    UPCOMING,
    PLANNED,
}
