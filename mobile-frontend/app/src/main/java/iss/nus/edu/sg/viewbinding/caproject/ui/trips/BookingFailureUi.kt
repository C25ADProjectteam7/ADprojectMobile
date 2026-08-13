package iss.nus.edu.sg.viewbinding.caproject.ui.trips

import android.content.Context
import iss.nus.edu.sg.viewbinding.caproject.R
import iss.nus.edu.sg.viewbinding.caproject.network.ApiFailureKind
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult

fun Context.bookingMessageFor(failure: ApiResult.Failure): String {
    failure.message?.takeIf(String::isNotBlank)?.let { return it }
    return getString(
        when (failure.kind) {
            ApiFailureKind.UNAUTHORIZED -> R.string.trip_session_expired
            ApiFailureKind.FORBIDDEN -> R.string.booking_forbidden
            ApiFailureKind.VALIDATION -> R.string.booking_validation_failed
            ApiFailureKind.NOT_FOUND -> R.string.booking_not_found
            ApiFailureKind.CONFLICT -> R.string.booking_conflict
            ApiFailureKind.NETWORK -> R.string.booking_network_error
            ApiFailureKind.SERVER -> R.string.booking_server_error
            ApiFailureKind.INVALID_RESPONSE -> R.string.booking_invalid_response
            ApiFailureKind.UNKNOWN -> R.string.booking_unknown_error
        },
    )
}

fun ApiResult.Failure.isBookingRetryable(): Boolean {
    return kind in setOf(
        ApiFailureKind.NETWORK,
        ApiFailureKind.SERVER,
        ApiFailureKind.INVALID_RESPONSE,
        ApiFailureKind.UNKNOWN,
    )
}
