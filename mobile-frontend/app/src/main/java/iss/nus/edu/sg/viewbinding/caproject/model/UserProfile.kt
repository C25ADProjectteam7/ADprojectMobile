package iss.nus.edu.sg.viewbinding.caproject.model

data class UserProfile(
    val id: Long,
    val username: String,
    val email: String,
    val department: String,
    val phone: String,
    val role: String,
)
