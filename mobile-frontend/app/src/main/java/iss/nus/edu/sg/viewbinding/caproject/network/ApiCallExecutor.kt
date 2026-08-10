package iss.nus.edu.sg.viewbinding.caproject.network

import com.google.gson.Gson
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
    } catch (_: IOException) {
        ApiResult.Failure(ApiFailureKind.NETWORK)
    } catch (_: Exception) {
        ApiResult.Failure(ApiFailureKind.UNKNOWN)
    }
}

object ApiErrorParser {

    fun fromHttp(statusCode: Int, rawBody: String?, gson: Gson): ApiResult.Failure {
        val message = runCatching {
            gson.fromJson(rawBody, ApiErrorResponse::class.java)?.message
        }.getOrNull()?.takeIf { it.isNotBlank() }

        val kind = when (statusCode) {
            401 -> ApiFailureKind.UNAUTHORIZED
            in 400..499 -> ApiFailureKind.VALIDATION
            in 500..599 -> ApiFailureKind.SERVER
            else -> ApiFailureKind.UNKNOWN
        }
        return ApiResult.Failure(kind = kind, message = message)
    }
}
