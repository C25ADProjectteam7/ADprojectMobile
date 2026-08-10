package iss.nus.edu.sg.viewbinding.caproject.network

import iss.nus.edu.sg.viewbinding.caproject.network.model.LoginRequest
import iss.nus.edu.sg.viewbinding.caproject.network.model.LoginResponse
import iss.nus.edu.sg.viewbinding.caproject.network.model.RegisterRequest
import iss.nus.edu.sg.viewbinding.caproject.network.model.RegisterResponse
import retrofit2.http.Body
import retrofit2.http.POST

interface AuthApi {

    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    @POST("api/auth/register")
    suspend fun register(@Body request: RegisterRequest): RegisterResponse
}
