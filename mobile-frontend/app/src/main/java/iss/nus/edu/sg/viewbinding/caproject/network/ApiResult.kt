package iss.nus.edu.sg.viewbinding.caproject.network

sealed interface ApiResult<out T> {

    data class Success<T>(val value: T) : ApiResult<T>

    data class Failure(
        val kind: ApiFailureKind,
        val message: String? = null,
        val statusCode: Int? = null,
        val backendCode: Int? = null,
    ) : ApiResult<Nothing>
}

enum class ApiFailureKind {
    UNAUTHORIZED,
    FORBIDDEN,
    VALIDATION,
    NOT_FOUND,
    CONFLICT,
    SERVER,
    NETWORK,
    INVALID_RESPONSE,
    UNKNOWN,
}
