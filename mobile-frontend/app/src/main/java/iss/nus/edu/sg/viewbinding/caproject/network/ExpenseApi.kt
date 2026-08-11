package iss.nus.edu.sg.viewbinding.caproject.network

import iss.nus.edu.sg.viewbinding.caproject.network.model.common.ApiResponse
import iss.nus.edu.sg.viewbinding.caproject.network.model.expense.ExpenseResponse
import iss.nus.edu.sg.viewbinding.caproject.network.model.expense.ExpenseSubmitRequest
import java.math.BigDecimal
import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface ExpenseApi {

    @GET("api/expenses")
    suspend fun getExpenses(): List<ExpenseResponse>

    @GET("api/expenses/{id}")
    suspend fun getExpense(@Path("id") id: Long): ExpenseResponse

    @POST("api/expenses/{tripId}")
    suspend fun submitExpense(
        @Path("tripId") tripId: Long,
        @Body request: ExpenseSubmitRequest,
    ): ExpenseResponse

    @Multipart
    @POST("api/expenses/upload-receipt")
    suspend fun uploadReceipt(
        @Part file: MultipartBody.Part,
        @Query("tripId") tripId: Long,
        @Query("category") category: String,
        @Query("amount") amount: BigDecimal,
        @Query("currency") currency: String? = null,
        @Query("description") description: String? = null,
    ): ApiResponse<ExpenseResponse>
}
