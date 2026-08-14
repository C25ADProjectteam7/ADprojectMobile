package iss.nus.edu.sg.viewbinding.caproject.network

import iss.nus.edu.sg.viewbinding.caproject.network.model.common.ApiResponse
import iss.nus.edu.sg.viewbinding.caproject.network.model.user.ResetPasswordRequest
import iss.nus.edu.sg.viewbinding.caproject.network.model.user.UpdateProfileRequest
import iss.nus.edu.sg.viewbinding.caproject.network.model.user.UserResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT

interface UserApi {

    @GET("api/users/me")
    suspend fun getMe(): ApiResponse<UserResponse>

    @PUT("api/users/me")
    suspend fun updateMe(@Body request: UpdateProfileRequest): ApiResponse<UserResponse>

    @PUT("api/users/me/password")
    suspend fun resetPassword(@Body request: ResetPasswordRequest): ApiResponse<Unit>
}
