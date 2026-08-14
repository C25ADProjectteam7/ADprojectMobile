package iss.nus.edu.sg.viewbinding.caproject.ui.expense

import android.content.Context
import android.widget.TextView
import iss.nus.edu.sg.viewbinding.caproject.R
import iss.nus.edu.sg.viewbinding.caproject.model.ExpenseRecord
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object ExpenseUiFormatter {

    private val dateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val submittedFormatter = DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", Locale.ENGLISH)

    fun amount(value: BigDecimal, currency: String): String {
        val formatter = NumberFormat.getNumberInstance(Locale.ENGLISH).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        val prefix = if (currency.equals("SGD", ignoreCase = true)) "S$" else "${currency.uppercase()} "
        return "$prefix${formatter.format(value)}"
    }

    fun details(expense: ExpenseRecord): String {
        return "${expense.expenseDate.format(dateFormatter)} · ${category(expense.category)}"
    }

    /** "✈ Tokyo · Tokyo Business Trip" from the claim's trip context, or
     * null when the backend sent neither field (older API). */
    fun tripLabel(expense: ExpenseRecord): String? {
        val parts = listOfNotNull(expense.tripDestination, expense.tripTitle)
            .filter(String::isNotBlank)
        return parts.joinToString(" · ").takeIf(String::isNotBlank)
    }

    fun submittedAt(expense: ExpenseRecord): String = expense.submittedAt.format(submittedFormatter)

    fun category(category: String): String {
        return category.lowercase(Locale.ENGLISH)
            .replaceFirstChar { it.titlecase(Locale.ENGLISH) }
    }

    fun bindStatus(context: Context, view: TextView, status: String) {
        when (status.uppercase(Locale.ENGLISH)) {
            ExpenseRecord.STATUS_APPROVED -> {
                view.setText(R.string.expense_status_approved)
                view.setTextColor(context.getColor(R.color.travel_green))
                view.setBackgroundResource(R.drawable.bg_status_green)
            }
            ExpenseRecord.STATUS_REJECTED -> {
                view.setText(R.string.expense_status_rejected)
                view.setTextColor(context.getColor(R.color.travel_red))
                view.setBackgroundResource(R.drawable.bg_status_red)
            }
            ExpenseRecord.STATUS_SUBMITTED -> {
                view.setText(R.string.expense_status_submitted)
                view.setTextColor(context.getColor(R.color.travel_gold_dark))
                view.setBackgroundResource(R.drawable.bg_status_gold)
            }
            else -> {
                view.setText(R.string.expense_status_unknown)
                view.setTextColor(context.getColor(R.color.travel_text_muted))
                view.setBackgroundResource(R.drawable.bg_status_gold)
            }
        }
    }
}
