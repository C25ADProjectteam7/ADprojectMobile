package iss.nus.edu.sg.viewbinding.caproject.network.model

data class RegisterRequest(
    val username: String,
    val password: String,
    val email: String?,
    val department: String?,
    val phone: String?,
)
