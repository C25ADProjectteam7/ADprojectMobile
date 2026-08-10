package iss.nus.edu.sg.viewbinding.caproject.data.mock

import iss.nus.edu.sg.viewbinding.caproject.model.ClaimSummary
import iss.nus.edu.sg.viewbinding.caproject.model.ExpenseEntry
import iss.nus.edu.sg.viewbinding.caproject.model.PolicyResult
import java.time.LocalDate

object MockExpenseData {

    fun policyResultFor(category: String, amount: Double): PolicyResult {
        val limit = when (category) {
            "Meals & entertainment" -> 80.0
            "Local transport" -> 120.0
            "Accommodation" -> 350.0
            else -> 200.0
        }
        return if (amount <= limit) PolicyResult.WITHIN_POLICY else PolicyResult.REVIEW_REQUIRED
    }

    fun claimReferenceFor(date: LocalDate): String {
        return "CLM-${date.year}-${date.monthValue.toString().padStart(2, '0')}${date.dayOfMonth.toString().padStart(2, '0')}"
    }

    fun claimFor(expense: ExpenseEntry): ClaimSummary = ClaimSummary(
        reference = expense.claimReference,
        destination = expense.destination,
        amount = expense.amount,
        merchant = expense.merchant,
        receiptName = expense.receiptName,
        policyResult = expense.policyResult,
        status = expense.claimStatus,
        submittedAt = expense.submittedAt,
    )
}
