package iss.nus.edu.sg.viewbinding.caproject.network.model

import com.google.gson.JsonElement

data class ApiErrorResponse(
    val code: Int? = null,
    val message: String? = null,
    val data: JsonElement? = null,
)
