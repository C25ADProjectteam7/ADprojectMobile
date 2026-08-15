package iss.nus.edu.sg.viewbinding.caproject.model

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class ExpenseRecord(
    val id: Long,
    val tripId: Long,
    val category: String,
    val amount: BigDecimal,
    val currency: String,
    val description: String?,
    val receiptUrl: String?,
    val status: String,
    val submittedAt: LocalDateTime,
    val merchant: String,
    val expenseDate: LocalDate,
    val notes: String,
    val tripTitle: String? = null,
    val tripDestination: String? = null,
    val approvalOpinion: String? = null,
    val approverName: String? = null,
) {
    val receiptName: String
        get() = receiptUrl?.substringAfterLast('/')?.takeIf(String::isNotBlank).orEmpty()

    companion object {
        const val STATUS_SUBMITTED = "SUBMITTED"
        const val STATUS_APPROVED = "APPROVED"
        const val STATUS_REJECTED = "REJECTED"
        const val STATUS_NEEDS_INFO = "NEEDS_INFO"
    }
}

data class ReceiptUpload(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
)
