package iss.nus.edu.sg.viewbinding.caproject.data.repository

import android.content.Context
import iss.nus.edu.sg.viewbinding.caproject.data.local.AgentTaskDataSource
import iss.nus.edu.sg.viewbinding.caproject.data.local.AgentTaskStore
import iss.nus.edu.sg.viewbinding.caproject.model.TripRequestData
import iss.nus.edu.sg.viewbinding.caproject.network.ApiFailureKind
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult
import java.math.BigDecimal

class AgentTripPlanner(
    private val tripRepository: TripRepository,
    private val agentRepository: AgentRepository,
    private val taskStore: AgentTaskDataSource,
) {

    suspend fun resumeIfPresent(
        trip: TripRequestData,
        onProgress: (AgentTaskProgress) -> Unit = {},
    ): AgentPollResult? {
        val tripId = trip.remoteId ?: return AgentPollResult.InvalidResponse
        val taskId = taskStore.taskIdFor(tripId) ?: return null
        onProgress(AgentTaskProgress(taskId, STATUS_RESUMING))
        return awaitStoredTask(tripId, taskId, onProgress)
    }

    suspend fun generateOrResume(
        trip: TripRequestData,
        requestedChange: String? = null,
        onProgress: (AgentTaskProgress) -> Unit = {},
    ): AgentPollResult {
        val tripId = trip.remoteId ?: return AgentPollResult.InvalidResponse
        val storedTaskId = taskStore.taskIdFor(tripId)
        val taskId = if (storedTaskId == null) {
            val isModification = !requestedChange.isNullOrBlank()
            // A modification request goes to the dedicated modify endpoint
            // (which edits the EXISTING itinerary in place) with just the
            // change text - sending it through agent-chat would regenerate
            // the whole trip from scratch and silently drop the change
            // (observed live: "Add a trip to zoo" produced a zoo-less
            // itinerary that also wiped the previous day plans).
            val message = if (isModification) {
                requestedChange.trim()
            } else {
                AgentTripMessageFactory.initialRequest(trip)
            }
            val startResult = if (isModification) {
                tripRepository.startAgentModify(tripId, message)
            } else {
                tripRepository.startAgentChat(tripId, message)
            }
            when (startResult) {
                is ApiResult.Failure -> return AgentPollResult.RequestFailure(startResult)
                is ApiResult.Success -> {
                    val startedTaskId = runCatching { startResult.value.taskId.trim() }
                        .getOrNull()
                        ?.takeIf(String::isNotBlank)
                        ?: return AgentPollResult.InvalidResponse
                    taskStore.save(tripId, startedTaskId)
                    val status = runCatching { startResult.value.status.trim() }
                        .getOrNull()
                        ?.takeIf(String::isNotBlank)
                        ?: STATUS_PROCESSING
                    onProgress(AgentTaskProgress(startedTaskId, status))
                    startedTaskId
                }
            }
        } else {
            onProgress(AgentTaskProgress(storedTaskId, STATUS_RESUMING))
            storedTaskId
        }

        return awaitStoredTask(tripId, taskId, onProgress)
    }

    private suspend fun awaitStoredTask(
        tripId: Long,
        taskId: String,
        onProgress: (AgentTaskProgress) -> Unit,
    ): AgentPollResult {
        val pollResult = agentRepository.awaitTask(taskId) { task ->
            val status = runCatching { task.status.trim() }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
                ?: STATUS_PROCESSING
            onProgress(AgentTaskProgress(taskId, status, task.stage))
        }
        if (pollResult.shouldClearStoredTask()) taskStore.remove(tripId)
        return pollResult
    }

    private fun AgentPollResult.shouldClearStoredTask(): Boolean {
        return when (this) {
            AgentPollResult.ItineraryReady,
            is AgentPollResult.NeedsMoreInfo,
            is AgentPollResult.TaskFailed,
            AgentPollResult.InvalidResponse,
            -> true

            is AgentPollResult.RequestFailure -> failure.kind == ApiFailureKind.NOT_FOUND
            AgentPollResult.TimedOut -> false
        }
    }

    companion object {
        private const val STATUS_RESUMING = "RESUMING"
        private const val STATUS_PROCESSING = "PROCESSING"

        fun create(context: Context): AgentTripPlanner {
            val applicationContext = context.applicationContext
            return AgentTripPlanner(
                tripRepository = TripRepository.create(applicationContext),
                agentRepository = AgentRepository.create(applicationContext),
                taskStore = AgentTaskStore(applicationContext),
            )
        }
    }
}

data class AgentTaskProgress(
    val taskId: String,
    val status: String,
    // Backend streaming stage (e.g. "searching_flights_hotels") while the
    // task is PROCESSING; null when the backend didn't report one yet.
    val stage: String? = null,
)

object AgentTripMessageFactory {

    fun initialRequest(trip: TripRequestData): String {
        return buildTripFacts(trip) + " Create a complete day-by-day itinerary."
    }

    fun modificationRequest(trip: TripRequestData, requestedChange: String): String {
        return buildTripFacts(trip) +
            " Keep these trip facts and regenerate the itinerary with this requested change: ${requestedChange.trim()}"
    }

    private fun buildTripFacts(trip: TripRequestData): String {
        val budget = BigDecimal.valueOf(trip.budget).stripTrailingZeros().toPlainString()
        val preferences = trip.preferences.joinToString().ifBlank { "No special preferences" }
        val notes = trip.notes.trim().takeIf(String::isNotBlank)?.let { " Notes: $it." }.orEmpty()
        return "Plan a business trip from Singapore to ${trip.destination} " +
            "from ${trip.startDate} to ${trip.endDate}. Total budget SGD $budget. " +
            "Preferences: $preferences.$notes"
    }
}
