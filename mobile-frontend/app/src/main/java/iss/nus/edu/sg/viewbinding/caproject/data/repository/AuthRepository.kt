package iss.nus.edu.sg.viewbinding.caproject.data.repository

import android.content.Context
import iss.nus.edu.sg.viewbinding.caproject.network.ApiClient
import iss.nus.edu.sg.viewbinding.caproject.network.ApiFailureKind
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult
import iss.nus.edu.sg.viewbinding.caproject.network.AuthApi
import iss.nus.edu.sg.viewbinding.caproject.network.executeApiCall
import iss.nus.edu.sg.viewbinding.caproject.network.model.LoginRequest
import iss.nus.edu.sg.viewbinding.caproject.network.model.RegisterRequest
import iss.nus.edu.sg.viewbinding.caproject.network.model.RegisterResponse
import iss.nus.edu.sg.viewbinding.caproject.session.SessionManager
import iss.nus.edu.sg.viewbinding.caproject.session.UserSession

class AuthRepository(
    private val authApi: AuthApi,
    private val sessionManager: SessionManager,
) {

    suspend fun login(username: String, password: String): ApiResult<UserSession> {
        return when (
            val result = executeApiCall(ApiClient.gson()) {
                authApi.login(LoginRequest(username = username, password = password))
            }
        ) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> {
                sessionManager.save(result.value)
                val session = sessionManager.currentSession()
                if (session == null) {
                    ApiResult.Failure(ApiFailureKind.INVALID_RESPONSE)
                } else {
                    ApiResult.Success(session)
                }
            }
        }
    }

    suspend fun register(request: RegisterRequest): ApiResult<RegisterResponse> {
        return executeApiCall(ApiClient.gson()) { authApi.register(request) }
    }

    fun currentSession(): UserSession? = sessionManager.currentSession()

    fun logout() = sessionManager.clear()

    companion object {
        fun create(context: Context): AuthRepository {
            val applicationContext = context.applicationContext
            return AuthRepository(
                authApi = ApiClient.authApi(applicationContext),
                sessionManager = SessionManager(applicationContext),
            )
        }
    }
}
