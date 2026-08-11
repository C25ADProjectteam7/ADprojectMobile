package iss.nus.edu.sg.viewbinding.caproject.ui.profile

import android.content.Context
import iss.nus.edu.sg.viewbinding.caproject.R
import iss.nus.edu.sg.viewbinding.caproject.network.ApiFailureKind
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult

fun Context.profileMessageFor(failure: ApiResult.Failure): String {
    failure.message?.takeIf(String::isNotBlank)?.let { return it }
    return getString(
        when (failure.kind) {
            ApiFailureKind.UNAUTHORIZED -> R.string.trip_session_expired
            ApiFailureKind.FORBIDDEN -> R.string.profile_forbidden
            ApiFailureKind.VALIDATION -> R.string.profile_validation_failed
            ApiFailureKind.NOT_FOUND -> R.string.profile_not_found
            ApiFailureKind.CONFLICT -> R.string.profile_conflict
            ApiFailureKind.NETWORK -> R.string.profile_network_error
            ApiFailureKind.SERVER -> R.string.profile_server_error
            ApiFailureKind.INVALID_RESPONSE -> R.string.profile_invalid_response
            ApiFailureKind.UNKNOWN -> R.string.profile_unknown_error
        },
    )
}

fun ApiResult.Failure.isProfileRetryable(): Boolean {
    return kind in setOf(
        ApiFailureKind.NETWORK,
        ApiFailureKind.SERVER,
        ApiFailureKind.INVALID_RESPONSE,
        ApiFailureKind.UNKNOWN,
    )
}
