package iss.nus.edu.sg.viewbinding.caproject.session

data class UserSession(
    val accessToken: String,
    val tokenType: String,
    val expiresAtEpochMillis: Long,
    val userId: Long,
    val username: String,
    val role: String,
)
