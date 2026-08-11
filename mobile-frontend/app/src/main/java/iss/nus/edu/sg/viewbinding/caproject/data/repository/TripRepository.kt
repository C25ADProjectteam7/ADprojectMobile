package iss.nus.edu.sg.viewbinding.caproject.data.repository

import android.content.Context
import com.google.gson.Gson
import iss.nus.edu.sg.viewbinding.caproject.data.local.TripDraftDataSource
import iss.nus.edu.sg.viewbinding.caproject.data.local.TripDraftStore
import iss.nus.edu.sg.viewbinding.caproject.model.TripDetailData
import iss.nus.edu.sg.viewbinding.caproject.model.TripRequestData
import iss.nus.edu.sg.viewbinding.caproject.network.ApiClient
import iss.nus.edu.sg.viewbinding.caproject.network.ApiFailureKind
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult
import iss.nus.edu.sg.viewbinding.caproject.network.TripApi
import iss.nus.edu.sg.viewbinding.caproject.network.executeApiCall
import iss.nus.edu.sg.viewbinding.caproject.network.model.agent.AgentChatRequest
import iss.nus.edu.sg.viewbinding.caproject.network.model.agent.AgentTaskStartResponse
import iss.nus.edu.sg.viewbinding.caproject.network.model.common.MessageResponse

class TripRepository(
    private val tripApi: TripApi,
    private val gson: Gson,
    private val draftStore: TripDraftDataSource,
) {

    suspend fun getTrips(): ApiResult<List<TripRequestData>> {
        return executeAndMap({ tripApi.getTrips() }) { response ->
            response.map { trip ->
                trip.toTripRequestData(
                    preferences = draftStore.preferencesFor(trip.id),
                    notes = draftStore.notesFor(trip.id),
                )
            }
        }
    }

    suspend fun getTrip(tripId: Long): ApiResult<TripRequestData> {
        return executeAndMap({ tripApi.getTrip(tripId) }) { trip ->
            trip.toTripRequestData(
                preferences = draftStore.preferencesFor(trip.id),
                notes = draftStore.notesFor(trip.id),
            )
        }
    }

    suspend fun getTripDetail(tripId: Long): ApiResult<TripDetailData> {
        return executeAndMap({ tripApi.getTripDetail(tripId) }) { detail ->
            detail.toTripDetailData(
                preferences = draftStore.preferencesFor(detail.id),
                notes = draftStore.notesFor(detail.id),
            )
        }
    }

    suspend fun createTrip(request: TripRequestData): ApiResult<TripRequestData> {
        val result = executeAndMap({ tripApi.createTrip(request.toNetworkRequest()) }) { trip ->
            trip.toTripRequestData(
                preferences = request.preferences,
                notes = request.notes,
            )
        }
        if (result is ApiResult.Success) {
            draftStore.save(result.value.remoteId ?: return invalidResponse(), request.preferences, request.notes)
        }
        return result
    }

    suspend fun updateTrip(tripId: Long, request: TripRequestData): ApiResult<TripRequestData> {
        val result = executeAndMap({ tripApi.updateTrip(tripId, request.toNetworkRequest()) }) { trip ->
            trip.toTripRequestData(
                preferences = request.preferences,
                notes = request.notes,
            )
        }
        if (result is ApiResult.Success) {
            draftStore.save(tripId, request.preferences, request.notes)
        }
        return result
    }

    suspend fun cancelTrip(tripId: Long): ApiResult<MessageResponse> {
        val result = executeApiCall(gson) { tripApi.cancelTrip(tripId) }
        if (result is ApiResult.Success) draftStore.remove(tripId)
        return result
    }

    suspend fun startAgentChat(tripId: Long, message: String): ApiResult<AgentTaskStartResponse> {
        return executeApiCall(gson) {
            tripApi.startAgentChat(tripId, AgentChatRequest(message = message))
        }
    }

    private suspend fun <Network, Domain> executeAndMap(
        request: suspend () -> Network,
        mapper: (Network) -> Domain,
    ): ApiResult<Domain> {
        return when (val result = executeApiCall(gson, request)) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> runCatching { mapper(result.value) }
                .fold(
                    onSuccess = { ApiResult.Success(it) },
                    onFailure = { invalidResponse() },
                )
        }
    }

    private fun invalidResponse() = ApiResult.Failure(ApiFailureKind.INVALID_RESPONSE)

    companion object {
        fun create(context: Context): TripRepository {
            val applicationContext = context.applicationContext
            return TripRepository(
                tripApi = ApiClient.tripApi(applicationContext),
                gson = ApiClient.gson(),
                draftStore = TripDraftStore(applicationContext),
            )
        }
    }
}
