package iss.nus.edu.sg.viewbinding.caproject.model

import java.math.BigDecimal

data class BookingRecord(
    val id: Long,
    val tripId: Long,
    val type: String,
    val bookingRef: String?,
    val price: BigDecimal?,
    val currency: String?,
    val status: String,
) {
    val isCancelled: Boolean
        get() = status.equals(STATUS_CANCELLED, ignoreCase = true)

    companion object {
        const val TYPE_FLIGHT = "FLIGHT"
        const val TYPE_HOTEL = "HOTEL"
        const val STATUS_CANCELLED = "CANCELLED"
        const val STATUS_FAILED = "FAILED"
    }
}
