package iss.nus.edu.sg.viewbinding.caproject.network

import iss.nus.edu.sg.viewbinding.caproject.network.model.booking.BookingRequest
import iss.nus.edu.sg.viewbinding.caproject.network.model.booking.BookingResponse
import iss.nus.edu.sg.viewbinding.caproject.network.model.common.MessageResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

interface BookingApi {

    @GET("api/bookings")
    suspend fun getBookings(): List<BookingResponse>

    @GET("api/bookings/{id}")
    suspend fun getBooking(@Path("id") id: Long): BookingResponse

    @POST("api/bookings/{tripId}")
    suspend fun createBooking(
        @Path("tripId") tripId: Long,
        @Body request: BookingRequest,
    ): BookingResponse

    @PUT("api/bookings/{id}/cancel")
    suspend fun cancelBooking(@Path("id") id: Long): MessageResponse
}
