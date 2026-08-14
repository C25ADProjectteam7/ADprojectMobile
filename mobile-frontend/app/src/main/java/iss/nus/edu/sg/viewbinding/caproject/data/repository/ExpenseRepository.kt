package iss.nus.edu.sg.viewbinding.caproject.data.repository

import android.content.Context
import com.google.gson.Gson
import iss.nus.edu.sg.viewbinding.caproject.model.ExpenseRecord
import iss.nus.edu.sg.viewbinding.caproject.model.ReceiptUpload
import iss.nus.edu.sg.viewbinding.caproject.network.ApiClient
import iss.nus.edu.sg.viewbinding.caproject.network.ApiFailureKind
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult
import iss.nus.edu.sg.viewbinding.caproject.network.ExpenseApi
import iss.nus.edu.sg.viewbinding.caproject.network.executeApiCall
import iss.nus.edu.sg.viewbinding.caproject.network.model.expense.ExpenseResponse
import iss.nus.edu.sg.viewbinding.caproject.network.model.expense.ExpenseSubmitRequest
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.Locale
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

class ExpenseRepository(
    private val expenseApi: ExpenseApi,
    private val gson: Gson,
) {

    suspend fun getExpenses(): ApiResult<List<ExpenseRecord>> {
        return executeAndMap({ expenseApi.getExpenses() }) { responses ->
            responses.map { it.toExpenseRecord() }.sortedByDescending(ExpenseRecord::submittedAt)
        }
    }

    suspend fun getExpense(expenseId: Long): ApiResult<ExpenseRecord> {
        return executeAndMap({ expenseApi.getExpense(expenseId) }) { it.toExpenseRecord() }
    }

    suspend fun submitExpense(
        tripId: Long,
        category: String,
        amount: BigDecimal,
        currency: String,
        description: String,
        receiptUrl: String? = null,
    ): ApiResult<ExpenseRecord> {
        val request = ExpenseSubmitRequest(
            category = category,
            amount = amount,
            currency = currency,
            description = description,
            receiptUrl = receiptUrl,
        )
        return executeAndMap({ expenseApi.submitExpense(tripId, request) }) {
            it.toExpenseRecord()
        }
    }

    suspend fun uploadReceipt(
        tripId: Long,
        category: String,
        amount: BigDecimal,
        currency: String,
        description: String,
        receipt: ReceiptUpload,
    ): ApiResult<ExpenseRecord> {
        val mediaType = receipt.mimeType.toMediaType()
        val fileBody = receipt.bytes.toRequestBody(mediaType)
        val filePart = MultipartBody.Part.createFormData("file", receipt.fileName, fileBody)
        return when (
            val result = executeApiCall(gson) {
                expenseApi.uploadReceipt(
                    file = filePart,
                    tripId = tripId,
                    category = category,
                    amount = amount,
                    currency = currency,
                    description = description,
                )
            }
        ) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> runCatching {
                require(result.value.code in 200..299)
                requireNotNull(result.value.data).toExpenseRecord()
            }.fold(
                onSuccess = { ApiResult.Success(it) },
                onFailure = { invalidResponse() },
            )
        }
    }

    private suspend fun <Network, Domain> executeAndMap(
        request: suspend () -> Network,
        mapper: (Network) -> Domain,
    ): ApiResult<Domain> {
        return when (val result = executeApiCall(gson, request)) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> runCatching { mapper(result.value) }
                .fold(
                    onSuccess = { ApiResult.Success(it) },
                    onFailure = { invalidResponse() },
                )
        }
    }

    private fun ExpenseResponse.toExpenseRecord(): ExpenseRecord {
        require(id > 0 && tripId > 0 && userId > 0 && amount > BigDecimal.ZERO)
        val normalizedCategory = category.trim().uppercase(Locale.ENGLISH)
        require(normalizedCategory in VALID_CATEGORIES)
        val normalizedStatus = status.trim().uppercase(Locale.ENGLISH)
        require(normalizedStatus in VALID_STATUSES)
        val normalizedCurrency = currency.trim().uppercase(Locale.ENGLISH)
        require(normalizedCurrency.length == 3)
        val submittedDateTime = parseDateTime(submittedAt)
        val metadata = ExpenseDescriptionCodec.decode(description)
        return ExpenseRecord(
            id = id,
            tripId = tripId,
            category = normalizedCategory,
            amount = amount,
            currency = normalizedCurrency,
            description = description,
            receiptUrl = receiptUrl?.takeIf(String::isNotBlank),
            status = normalizedStatus,
            submittedAt = submittedDateTime,
            merchant = metadata.merchant ?: normalizedCategory.toDisplayLabel(),
            expenseDate = metadata.date ?: submittedDateTime.toLocalDate(),
            notes = metadata.notes,
            tripTitle = tripTitle?.takeIf(String::isNotBlank),
            tripDestination = tripDestination?.takeIf(String::isNotBlank),
        )
    }

    private fun parseDateTime(raw: String): LocalDateTime {
        return runCatching { LocalDateTime.parse(raw) }
            .recoverCatching { OffsetDateTime.parse(raw).toLocalDateTime() }
            .getOrThrow()
    }

    private fun String.toDisplayLabel(): String {
        return lowercase(Locale.ENGLISH).replaceFirstChar { it.titlecase(Locale.ENGLISH) }
    }

    private fun invalidResponse() = ApiResult.Failure(ApiFailureKind.INVALID_RESPONSE)

    companion object {
        private val VALID_CATEGORIES = setOf("FLIGHT", "HOTEL", "MEAL", "TRANSPORT", "OTHER")
        private val VALID_STATUSES = setOf(
            ExpenseRecord.STATUS_SUBMITTED,
            ExpenseRecord.STATUS_APPROVED,
            ExpenseRecord.STATUS_REJECTED,
        )

        fun create(context: Context): ExpenseRepository {
            val applicationContext = context.applicationContext
            return ExpenseRepository(
                expenseApi = ApiClient.expenseApi(applicationContext),
                gson = ApiClient.gson(),
            )
        }
    }
}
