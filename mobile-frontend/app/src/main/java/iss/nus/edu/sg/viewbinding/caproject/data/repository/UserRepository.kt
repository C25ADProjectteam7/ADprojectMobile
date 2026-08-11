package iss.nus.edu.sg.viewbinding.caproject.data.repository

import android.content.Context
import com.google.gson.Gson
import iss.nus.edu.sg.viewbinding.caproject.model.UserProfile
import iss.nus.edu.sg.viewbinding.caproject.network.ApiClient
import iss.nus.edu.sg.viewbinding.caproject.network.ApiFailureKind
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult
import iss.nus.edu.sg.viewbinding.caproject.network.UserApi
import iss.nus.edu.sg.viewbinding.caproject.network.executeApiCall
import iss.nus.edu.sg.viewbinding.caproject.network.model.common.ApiResponse
import iss.nus.edu.sg.viewbinding.caproject.network.model.user.UpdateProfileRequest
import iss.nus.edu.sg.viewbinding.caproject.network.model.user.UserResponse

class UserRepository(
    private val userApi: UserApi,
    private val gson: Gson,
) {

    suspend fun getProfile(): ApiResult<UserProfile> {
        return executeAndMap { userApi.getMe() }
    }

    suspend fun updateProfile(
        email: String,
        department: String,
        phone: String,
    ): ApiResult<UserProfile> {
        return executeAndMap {
            userApi.updateMe(
                UpdateProfileRequest(
                    email = email.trim(),
                    department = department.trim(),
                    phone = phone.trim(),
                ),
            )
        }
    }

    private suspend fun executeAndMap(
        request: suspend () -> ApiResponse<UserResponse>,
    ): ApiResult<UserProfile> {
        return when (val result = executeApiCall(gson, request)) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> runCatching {
                require(result.value.code in 200..299)
                requireNotNull(result.value.data).toUserProfile()
            }.fold(
                onSuccess = { ApiResult.Success(it) },
                onFailure = { ApiResult.Failure(ApiFailureKind.INVALID_RESPONSE) },
            )
        }
    }

    private fun UserResponse.toUserProfile(): UserProfile {
        require(id > 0 && username.isNotBlank() && role.isNotBlank())
        return UserProfile(
            id = id,
            username = username.trim(),
            email = email.orEmpty().trim(),
            department = department.orEmpty().trim(),
            phone = phone.orEmpty().trim(),
            role = role.trim(),
        )
    }

    companion object {
        fun create(context: Context): UserRepository {
            val applicationContext = context.applicationContext
            return UserRepository(
                userApi = ApiClient.userApi(applicationContext),
                gson = ApiClient.gson(),
            )
        }
    }
}
