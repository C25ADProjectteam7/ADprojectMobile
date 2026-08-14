package iss.nus.edu.sg.viewbinding.caproject.ui.trips

import androidx.annotation.StringRes
import iss.nus.edu.sg.viewbinding.caproject.R
import java.util.Locale

/**
 * Maps the backend's streaming task stage (pushed by agent-ml-service's
 * task_manager.set_task_stage() while an agent task is PROCESSING) to a
 * human-readable label. Unknown stages map to null so callers fall back to
 * the generic taskId/status text.
 */
object AgentStageLabels {

    @StringRes
    fun labelResFor(stage: String?): Int? {
        return when (stage?.trim()?.lowercase(Locale.ENGLISH)) {
            "understanding_request" -> R.string.agent_stage_understanding_request
            "resolving_locations" -> R.string.agent_stage_resolving_locations
            "searching_flights_hotels" -> R.string.agent_stage_searching_flights_hotels
            "assembling_itinerary" -> R.string.agent_stage_assembling_itinerary
            "completed" -> R.string.agent_stage_finishing
            else -> null
        }
    }
}
