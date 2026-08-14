package iss.nus.edu.sg.viewbinding.caproject.network.model.expense

import java.math.BigDecimal

data class ExpenseSubmitRequest(
    val category: String,
    val amount: BigDecimal,
    val currency: String?,
    val description: String?,
    val receiptUrl: String?,
)

data class ExpenseResponse(
    val id: Long,
    val tripId: Long,
    val userId: Long,
    val category: String,
    val amount: BigDecimal,
    val currency: String,
    val description: String?,
    val receiptUrl: String?,
    val status: String,
    val submittedAt: String,
    val tripTitle: String? = null,
    val tripDestination: String? = null,
)
