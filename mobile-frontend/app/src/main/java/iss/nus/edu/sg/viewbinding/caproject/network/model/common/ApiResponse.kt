package iss.nus.edu.sg.viewbinding.caproject.network.model.common

data class ApiResponse<T>(
    val code: Int,
    val message: String,
    val data: T?,
)
