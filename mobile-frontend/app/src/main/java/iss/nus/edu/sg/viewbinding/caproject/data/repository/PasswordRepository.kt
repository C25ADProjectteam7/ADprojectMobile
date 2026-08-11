package iss.nus.edu.sg.viewbinding.caproject.data.repository

import android.content.Context
import iss.nus.edu.sg.viewbinding.caproject.network.ApiClient
import iss.nus.edu.sg.viewbinding.caproject.network.ApiFailureKind
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult
import iss.nus.edu.sg.viewbinding.caproject.network.AuthApi
import iss.nus.edu.sg.viewbinding.caproject.network.UserApi
import iss.nus.edu.sg.viewbinding.caproject.network.executeApiCall
import iss.nus.edu.sg.viewbinding.caproject.network.model.user.ForgotPasswordRequest
import iss.nus.edu.sg.viewbinding.caproject.network.model.user.ResetPasswordRequest

class PasswordRepository(
    private val authApi: AuthApi,
    private val userApi: UserApi,
) {

    suspend fun forgotPassword(request: ForgotPasswordRequest): ApiResult<String> {
        return when (
            val result = executeApiCall(ApiClient.gson()) {
                authApi.forgotPassword(request)
            }
        ) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> ApiResult.Success(result.value.message)
        }
    }

    suspend fun resetPassword(request: ResetPasswordRequest): ApiResult<String> {
        return when (
            val result = executeApiCall(ApiClient.gson()) {
                userApi.resetPassword(request)
            }
        ) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> {
                val response = result.value
                if (response.code in 200..299) {
                    ApiResult.Success(response.message)
                } else {
                    ApiResult.Failure(
                        kind = ApiFailureKind.VALIDATION,
                        message = response.message,
                        backendCode = response.code,
                    )
                }
            }
        }
    }

    companion object {
        fun create(context: Context): PasswordRepository {
            val applicationContext = context.applicationContext
            return PasswordRepository(
                authApi = ApiClient.authApi(applicationContext),
                userApi = ApiClient.userApi(applicationContext),
            )
        }
    }
}
