package iss.nus.edu.sg.viewbinding.caproject.network.model.agent

import com.google.gson.JsonObject
import java.math.BigDecimal

data class ExtractRequirementsRequest(
    val userInput: String,
)

data class ExtractRequirementsResponse(
    val originCity: String? = null,
    val destination: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val budgetTotal: BigDecimal? = null,
    val preferences: List<String>? = null,
    val missingFields: List<String>? = null,
    val clarifyingQuestion: String? = null,
)

data class GenerateItineraryRequest(
    val originCity: String,
    val destination: String,
    val startDate: String,
    val endDate: String,
    val budgetTotal: BigDecimal,
    val preferences: List<String>,
)

data class ModifyItineraryRequest(
    val currentItinerary: JsonObject,
    val userRequest: String,
)

data class AgentChatRequest(
    val message: String,
)

data class AgentTaskStartResponse(
    val taskId: String,
    val status: String,
)

data class AgentTaskResponse(
    val status: String,
    val result: JsonObject? = null,
    val error: String? = null,
    val tripId: Long? = null,
    val createdAt: Long? = null,
    // Streaming progress from the backend (e.g. "searching_flights_hotels"),
    // pushed by the Agent while the task is PROCESSING - see AgentStageLabels
    // for the UI mapping.
    val stage: String? = null,
)
