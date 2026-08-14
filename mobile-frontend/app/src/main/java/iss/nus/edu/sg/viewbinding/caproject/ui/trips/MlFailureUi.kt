package iss.nus.edu.sg.viewbinding.caproject.ui.trips

import android.content.Context
import iss.nus.edu.sg.viewbinding.caproject.R
import iss.nus.edu.sg.viewbinding.caproject.network.ApiFailureKind
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult

fun Context.mlMessageFor(failure: ApiResult.Failure): String {
    failure.message?.takeIf(String::isNotBlank)?.let { return it }
    return getString(
        when (failure.kind) {
            ApiFailureKind.UNAUTHORIZED -> R.string.trip_session_expired
            ApiFailureKind.FORBIDDEN -> R.string.ml_forbidden
            ApiFailureKind.VALIDATION -> R.string.ml_validation_failed
            ApiFailureKind.NOT_FOUND -> R.string.ml_not_found
            ApiFailureKind.CONFLICT -> R.string.ml_conflict
            ApiFailureKind.NETWORK -> R.string.ml_network_error
            ApiFailureKind.SERVER -> R.string.ml_server_error
            ApiFailureKind.INVALID_RESPONSE -> R.string.ml_invalid_response
            ApiFailureKind.UNKNOWN -> R.string.ml_unknown_error
        },
    )
}

fun ApiResult.Failure.isMlRetryable(): Boolean {
    return kind in setOf(
        ApiFailureKind.NETWORK,
        ApiFailureKind.SERVER,
        ApiFailureKind.INVALID_RESPONSE,
        ApiFailureKind.UNKNOWN,
    )
}
