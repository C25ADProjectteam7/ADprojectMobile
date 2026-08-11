package iss.nus.edu.sg.viewbinding.caproject.network

import com.google.gson.JsonObject
import iss.nus.edu.sg.viewbinding.caproject.network.model.agent.AgentTaskResponse
import iss.nus.edu.sg.viewbinding.caproject.network.model.agent.ExtractRequirementsRequest
import iss.nus.edu.sg.viewbinding.caproject.network.model.agent.ExtractRequirementsResponse
import iss.nus.edu.sg.viewbinding.caproject.network.model.agent.GenerateItineraryRequest
import iss.nus.edu.sg.viewbinding.caproject.network.model.agent.ModifyItineraryRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface AgentApi {

    @POST("api/agent/extract-requirements")
    suspend fun extractRequirements(
        @Body request: ExtractRequirementsRequest,
    ): ExtractRequirementsResponse

    @POST("api/agent/generate-itinerary")
    suspend fun generateItinerary(@Body request: GenerateItineraryRequest): JsonObject

    @POST("api/agent/modify-itinerary")
    suspend fun modifyItinerary(@Body request: ModifyItineraryRequest): JsonObject

    @GET("api/agent/tasks/{taskId}")
    suspend fun getTask(@Path("taskId") taskId: String): AgentTaskResponse
}
