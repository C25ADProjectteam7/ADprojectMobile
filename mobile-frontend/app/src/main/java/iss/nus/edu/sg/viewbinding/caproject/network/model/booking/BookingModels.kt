package iss.nus.edu.sg.viewbinding.caproject.network.model.booking

import java.math.BigDecimal

data class BookingRequest(
    val type: String,
    val bookingRef: String,
    val price: BigDecimal?,
    val currency: String?,
)

data class BookingResponse(
    val id: Long,
    val tripId: Long,
    val userId: Long,
    val type: String,
    val bookingRef: String?,
    val price: BigDecimal?,
    val currency: String?,
    val status: String,
)
