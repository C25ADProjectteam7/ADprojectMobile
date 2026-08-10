package iss.nus.edu.sg.viewbinding.caproject.data.mock

import iss.nus.edu.sg.viewbinding.caproject.model.ClaimStatus
import iss.nus.edu.sg.viewbinding.caproject.model.ExpenseEntry
import iss.nus.edu.sg.viewbinding.caproject.model.PolicyResult
import java.time.LocalDate
import java.time.LocalDateTime

object CurrentExpenseStore {

    var latestExpense: ExpenseEntry? = null
        private set

    fun submit(
        destination: String,
        merchant: String,
        date: LocalDate,
        amount: Double,
        category: String,
        notes: String,
        receiptUri: String,
        receiptName: String,
        submittedAt: LocalDateTime = LocalDateTime.now(),
    ): ExpenseEntry {
        val expense = ExpenseEntry(
            claimReference = MockExpenseData.claimReferenceFor(date),
            destination = destination,
            merchant = merchant,
            date = date,
            amount = amount,
            category = category,
            notes = notes,
            receiptUri = receiptUri,
            receiptName = receiptName,
            policyResult = MockExpenseData.policyResultFor(category, amount),
            claimStatus = ClaimStatus.UNDER_REVIEW,
            submittedAt = submittedAt,
        )
        latestExpense = expense
        return expense
    }

    fun clear() {
        latestExpense = null
    }
}
