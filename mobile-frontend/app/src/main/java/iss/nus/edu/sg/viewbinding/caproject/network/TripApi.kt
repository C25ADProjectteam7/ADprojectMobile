package iss.nus.edu.sg.viewbinding.caproject.network

import iss.nus.edu.sg.viewbinding.caproject.network.model.agent.AgentChatRequest
import iss.nus.edu.sg.viewbinding.caproject.network.model.agent.AgentTaskStartResponse
import iss.nus.edu.sg.viewbinding.caproject.network.model.common.MessageResponse
import iss.nus.edu.sg.viewbinding.caproject.network.model.trip.TripDetailResponse
import iss.nus.edu.sg.viewbinding.caproject.network.model.trip.TripRequest
import iss.nus.edu.sg.viewbinding.caproject.network.model.trip.TripResponse
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

interface TripApi {

    @GET("api/trips")
    suspend fun getTrips(): List<TripResponse>

    @POST("api/trips")
    suspend fun createTrip(@Body request: TripRequest): TripResponse

    @GET("api/trips/{id}")
    suspend fun getTrip(@Path("id") id: Long): TripResponse

    @PUT("api/trips/{id}")
    suspend fun updateTrip(
        @Path("id") id: Long,
        @Body request: TripRequest,
    ): TripResponse

    @DELETE("api/trips/{id}")
    suspend fun cancelTrip(@Path("id") id: Long): MessageResponse

    @GET("api/trips/{id}/detail")
    suspend fun getTripDetail(@Path("id") id: Long): TripDetailResponse

    @POST("api/trips/{id}/agent-chat")
    suspend fun startAgentChat(
        @Path("id") id: Long,
        @Body request: AgentChatRequest,
    ): AgentTaskStartResponse

    @POST("api/trips/{id}/agent-modify")
    suspend fun startAgentModify(
        @Path("id") id: Long,
        @Body request: AgentChatRequest,
    ): AgentTaskStartResponse
}
