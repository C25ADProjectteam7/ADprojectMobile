package iss.nus.edu.sg.viewbinding.caproject.ui.trips

import android.content.Context
import iss.nus.edu.sg.viewbinding.caproject.R
import iss.nus.edu.sg.viewbinding.caproject.network.ApiFailureKind
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult

internal fun Context.tripMessageFor(failure: ApiResult.Failure): String {
    return failure.message ?: getString(
        when (failure.kind) {
            ApiFailureKind.UNAUTHORIZED -> R.string.trip_session_expired
            ApiFailureKind.FORBIDDEN -> R.string.trip_forbidden
            ApiFailureKind.VALIDATION -> R.string.trip_validation_failed
            ApiFailureKind.NOT_FOUND -> R.string.trip_not_found
            ApiFailureKind.CONFLICT -> R.string.trip_conflict
            ApiFailureKind.NETWORK -> R.string.trip_network_error
            ApiFailureKind.SERVER -> R.string.trip_server_error
            ApiFailureKind.INVALID_RESPONSE -> R.string.trip_invalid_response
            ApiFailureKind.UNKNOWN -> R.string.trip_unknown_error
        },
    )
}

internal fun ApiResult.Failure.isTripRetryable(): Boolean {
    return kind == ApiFailureKind.NETWORK ||
        kind == ApiFailureKind.SERVER ||
        kind == ApiFailureKind.UNKNOWN
}
