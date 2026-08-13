package iss.nus.edu.sg.viewbinding.caproject.network

import com.google.gson.Gson
import com.google.gson.JsonParseException
import iss.nus.edu.sg.viewbinding.caproject.network.model.ApiErrorResponse
import java.io.IOException
import retrofit2.HttpException

suspend fun <T> executeApiCall(
    gson: Gson,
    request: suspend () -> T,
): ApiResult<T> {
    return try {
        ApiResult.Success(request())
    } catch (error: HttpException) {
        ApiErrorParser.fromHttp(
            statusCode = error.code(),
            rawBody = error.response()?.errorBody()?.string(),
            gson = gson,
        )
    } catch (_: JsonParseException) {
        ApiResult.Failure(ApiFailureKind.INVALID_RESPONSE)
    } catch (_: IOException) {
        ApiResult.Failure(ApiFailureKind.NETWORK)
    } catch (_: Exception) {
        ApiResult.Failure(ApiFailureKind.UNKNOWN)
    }
}

object ApiErrorParser {

    fun fromHttp(statusCode: Int, rawBody: String?, gson: Gson): ApiResult.Failure {
        val errorResponse = runCatching {
            gson.fromJson(rawBody, ApiErrorResponse::class.java)
        }.getOrNull()
        val message = errorResponse?.message?.takeIf { it.isNotBlank() }

        val kind = when (statusCode) {
            401 -> ApiFailureKind.UNAUTHORIZED
            403 -> ApiFailureKind.FORBIDDEN
            404 -> ApiFailureKind.NOT_FOUND
            409 -> ApiFailureKind.CONFLICT
            400, 422 -> ApiFailureKind.VALIDATION
            in 400..499 -> ApiFailureKind.VALIDATION
            in 500..599 -> ApiFailureKind.SERVER
            else -> ApiFailureKind.UNKNOWN
        }
        return ApiResult.Failure(
            kind = kind,
            message = message,
            statusCode = statusCode,
            backendCode = errorResponse?.code,
        )
    }
}
