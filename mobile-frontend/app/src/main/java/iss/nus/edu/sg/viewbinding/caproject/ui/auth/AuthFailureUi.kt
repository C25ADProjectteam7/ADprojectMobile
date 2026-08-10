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
            ApiFailureKind.VALIDATION,
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
