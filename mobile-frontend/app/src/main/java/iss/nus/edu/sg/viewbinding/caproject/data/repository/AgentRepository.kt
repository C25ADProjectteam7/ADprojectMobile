package iss.nus.edu.sg.viewbinding.caproject.data.repository

import android.content.Context
import com.google.gson.Gson
import iss.nus.edu.sg.viewbinding.caproject.network.AgentApi
import iss.nus.edu.sg.viewbinding.caproject.network.ApiClient
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult
import iss.nus.edu.sg.viewbinding.caproject.network.executeApiCall
import iss.nus.edu.sg.viewbinding.caproject.network.model.agent.AgentTaskResponse
import kotlinx.coroutines.delay

class AgentRepository(
    private val agentApi: AgentApi,
    private val gson: Gson,
    private val pollingIntervalMillis: Long = DEFAULT_POLLING_INTERVAL_MILLIS,
    private val maxPollingAttempts: Int = DEFAULT_MAX_POLLING_ATTEMPTS,
    private val waitBeforeNextPoll: suspend (Long) -> Unit = { delay(it) },
) {

    suspend fun getTask(taskId: String): ApiResult<AgentTaskResponse> {
        return executeApiCall(gson) { agentApi.getTask(taskId) }
    }

    suspend fun awaitTask(
        taskId: String,
        onProcessing: (AgentTaskResponse) -> Unit = {},
    ): AgentPollResult {
        if (taskId.isBlank()) return AgentPollResult.InvalidResponse

        repeat(maxPollingAttempts.coerceAtLeast(1)) { attempt ->
            when (val result = getTask(taskId)) {
                is ApiResult.Failure -> return AgentPollResult.RequestFailure(result)
                is ApiResult.Success -> {
                    val task = result.value
                    val status = runCatching { task.status.trim().uppercase() }.getOrNull()
                        ?: return AgentPollResult.InvalidResponse
                    when (status) {
                        STATUS_PROCESSING -> {
                            onProcessing(task)
                            if (attempt == maxPollingAttempts.coerceAtLeast(1) - 1) {
                                return AgentPollResult.TimedOut
                            }
                            waitBeforeNextPoll(pollingIntervalMillis)
                        }

                        STATUS_DONE -> return task.toTerminalResult()
                        STATUS_FAILED -> return AgentPollResult.TaskFailed(task.error?.takeIf(String::isNotBlank))
                        else -> return AgentPollResult.InvalidResponse
                    }
                }
            }
        }
        return AgentPollResult.TimedOut
    }

    private fun AgentTaskResponse.toTerminalResult(): AgentPollResult {
        val taskResult = result ?: return AgentPollResult.InvalidResponse
        val resultStatus = runCatching {
            taskResult.get("status")?.asString?.trim()?.uppercase()
        }.getOrNull()
        return when (resultStatus) {
            RESULT_ITINERARY_READY -> AgentPollResult.ItineraryReady
            RESULT_NEEDS_MORE_INFO -> {
                val missingFields = taskResult.getAsJsonArray("missingFields")
                    ?.mapNotNull { element -> runCatching { element.asString }.getOrNull() }
                    .orEmpty()
                val question = taskResult.get("clarifyingQuestion")
                    ?.takeUnless { it.isJsonNull }
                    ?.asString
                    ?.takeIf(String::isNotBlank)
                AgentPollResult.NeedsMoreInfo(
                    missingFields = missingFields,
                    clarifyingQuestion = question,
                )
            }

            else -> AgentPollResult.InvalidResponse
        }
    }

    companion object {
        private const val DEFAULT_POLLING_INTERVAL_MILLIS = 2_000L
        private const val DEFAULT_MAX_POLLING_ATTEMPTS = 45
        private const val STATUS_PROCESSING = "PROCESSING"
        private const val STATUS_DONE = "DONE"
        private const val STATUS_FAILED = "FAILED"
        private const val RESULT_ITINERARY_READY = "ITINERARY_READY"
        private const val RESULT_NEEDS_MORE_INFO = "NEEDS_MORE_INFO"

        fun create(context: Context): AgentRepository {
            val applicationContext = context.applicationContext
            return AgentRepository(
                agentApi = ApiClient.agentApi(applicationContext),
                gson = ApiClient.gson(),
            )
        }
    }
}

sealed interface AgentPollResult {
    data object ItineraryReady : AgentPollResult

    data class NeedsMoreInfo(
        val missingFields: List<String>,
        val clarifyingQuestion: String?,
    ) : AgentPollResult

    data class TaskFailed(val message: String?) : AgentPollResult

    data object TimedOut : AgentPollResult

    data class RequestFailure(val failure: ApiResult.Failure) : AgentPollResult

    data object InvalidResponse : AgentPollResult
}
