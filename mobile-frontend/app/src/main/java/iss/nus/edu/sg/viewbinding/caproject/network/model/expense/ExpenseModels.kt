package iss.nus.edu.sg.viewbinding.caproject.network.model.expense

import com.google.gson.annotations.SerializedName
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
    @SerializedName(value = "approvalOpinion", alternate = ["approval_opinion"])
    val approvalOpinion: String? = null,
    @SerializedName(value = "approverName", alternate = ["approver_name"])
    val approverName: String? = null,
)
