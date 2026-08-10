package iss.nus.edu.sg.viewbinding.caproject.network.model

data class LoginResponse(
    val accessToken: String,
    val tokenType: String,
    val expiresIn: Long,
    val userId: Long,
    val username: String,
    val role: String,
)
