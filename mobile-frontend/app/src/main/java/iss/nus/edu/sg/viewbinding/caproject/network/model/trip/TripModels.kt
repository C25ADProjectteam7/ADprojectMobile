package iss.nus.edu.sg.viewbinding.caproject.network.model.trip

import java.math.BigDecimal

data class TripRequest(
    val title: String,
    val destination: String,
    val startDate: String,
    val endDate: String,
    val budgetTotal: BigDecimal,
    val preferences: List<String>,
)

data class TripResponse(
    val id: Long,
    val userId: Long,
    val title: String,
    val destination: String,
    val startDate: String,
    val endDate: String,
    val budgetTotal: BigDecimal,
    val status: String,
)

data class TripDetailResponse(
    val id: Long,
    val title: String,
    val destination: String,
    val startDate: String,
    val endDate: String,
    val budgetTotal: BigDecimal,
    val status: String,
    val approvalNote: String? = null,
    val itineraries: List<ItineraryResponse>,
)

data class ItineraryResponse(
    val id: Long,
    val dayNumber: Int,
    val date: String,
    val generatedByAgent: Boolean,
    val items: List<ItineraryItemResponse>,
)

data class ItineraryItemResponse(
    val id: Long,
    val type: String,
    val startTime: String?,
    val endTime: String?,
    val title: String,
    val description: String?,
    val location: String?,
    val bookingRef: String?,
    val price: BigDecimal?,
    val currency: String?,
)
