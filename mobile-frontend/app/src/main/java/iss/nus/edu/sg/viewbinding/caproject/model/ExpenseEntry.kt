package iss.nus.edu.sg.viewbinding.caproject.model

import java.time.LocalDate
import java.time.LocalDateTime

data class ExpenseEntry(
    val claimReference: String,
    val destination: String,
    val merchant: String,
    val date: LocalDate,
    val amount: Double,
    val category: String,
    val notes: String,
    val receiptUri: String,
    val receiptName: String,
    val policyResult: PolicyResult,
    val claimStatus: ClaimStatus,
    val submittedAt: LocalDateTime,
)

enum class PolicyResult {
    WITHIN_POLICY,
    REVIEW_REQUIRED,
}

enum class ClaimStatus {
    UNDER_REVIEW,
    APPROVED,
    REJECTED,
}

data class ClaimSummary(
    val reference: String,
    val destination: String,
    val amount: Double,
    val merchant: String,
    val receiptName: String,
    val policyResult: PolicyResult,
    val status: ClaimStatus,
    val submittedAt: LocalDateTime,
)
