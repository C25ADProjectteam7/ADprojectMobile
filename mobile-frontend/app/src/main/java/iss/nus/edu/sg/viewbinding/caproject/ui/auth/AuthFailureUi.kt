package iss.nus.edu.sg.viewbinding.caproject.ui.auth

import android.content.Context
import iss.nus.edu.sg.viewbinding.caproject.R
import iss.nus.edu.sg.viewbinding.caproject.network.ApiFailureKind
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult

internal fun Context.messageFor(failure: ApiResult.Failure): String {
    return failure.message ?: getString(
        when (failure.kind) {
            ApiFailureKind.UNAUTHORIZED -> R.string.invalid_credentials
            ApiFailureKind.NETWORK -> R.string.auth_network_error
            ApiFailureKind.SERVER -> R.string.auth_server_error
            ApiFailureKind.INVALID_RESPONSE -> R.string.auth_invalid_response
            ApiFailureKind.FORBIDDEN,
            ApiFailureKind.VALIDATION,
            ApiFailureKind.NOT_FOUND,
            ApiFailureKind.CONFLICT,
            ApiFailureKind.UNKNOWN,
            -> R.string.auth_unknown_error
        },
    )
}

internal fun ApiResult.Failure.isRetryable(): Boolean {
    return kind == ApiFailureKind.NETWORK ||
        kind == ApiFailureKind.SERVER ||
        kind == ApiFailureKind.UNKNOWN
}

internal fun Context.passwordMessageFor(failure: ApiResult.Failure): String {
    if (!failure.message.isNullOrBlank()) return failure.message

    return when (failure.kind) {
        ApiFailureKind.UNAUTHORIZED,
        ApiFailureKind.FORBIDDEN,
        ApiFailureKind.VALIDATION,
        ApiFailureKind.NOT_FOUND,
        ApiFailureKind.CONFLICT,
        -> getString(R.string.password_update_failed)

        else -> messageFor(failure)
    }
}
