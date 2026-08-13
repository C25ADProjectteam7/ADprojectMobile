package iss.nus.edu.sg.viewbinding.caproject.ui.expense

import android.content.Context
import iss.nus.edu.sg.viewbinding.caproject.R
import iss.nus.edu.sg.viewbinding.caproject.network.ApiFailureKind
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult

fun Context.expenseMessageFor(failure: ApiResult.Failure): String {
    failure.message?.takeIf(String::isNotBlank)?.let { return it }
    return getString(
        when (failure.kind) {
            ApiFailureKind.UNAUTHORIZED -> R.string.trip_session_expired
            ApiFailureKind.FORBIDDEN -> R.string.expense_forbidden
            ApiFailureKind.VALIDATION -> R.string.expense_validation_failed
            ApiFailureKind.NOT_FOUND -> R.string.expense_not_found
            ApiFailureKind.CONFLICT -> R.string.expense_conflict
            ApiFailureKind.NETWORK -> R.string.expense_network_error
            ApiFailureKind.SERVER -> R.string.expense_server_error
            ApiFailureKind.INVALID_RESPONSE -> R.string.expense_invalid_response
            ApiFailureKind.UNKNOWN -> R.string.expense_unknown_error
        },
    )
}

fun ApiResult.Failure.isExpenseRetryable(): Boolean {
    return kind in setOf(
        ApiFailureKind.NETWORK,
        ApiFailureKind.SERVER,
        ApiFailureKind.INVALID_RESPONSE,
        ApiFailureKind.UNKNOWN,
    )
}
