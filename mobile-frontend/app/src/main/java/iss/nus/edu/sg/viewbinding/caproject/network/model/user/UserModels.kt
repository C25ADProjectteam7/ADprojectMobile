package iss.nus.edu.sg.viewbinding.caproject.network.model.user

data class UserResponse(
    val id: Long,
    val username: String,
    val email: String?,
    val department: String?,
    val phone: String?,
    val role: String,
)

data class UpdateProfileRequest(
    val email: String? = null,
    val department: String? = null,
    val phone: String? = null,
)

data class ForgotPasswordRequest(
    val username: String,
    val email: String,
    val department: String,
    val phone: String,
    val newPassword: String,
)

data class ResetPasswordRequest(
    val username: String,
    val email: String,
    val department: String,
    val phone: String,
    val oldPassword: String,
    val newPassword: String,
)
