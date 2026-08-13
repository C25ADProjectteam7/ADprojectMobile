package iss.nus.edu.sg.viewbinding.caproject.network

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import iss.nus.edu.sg.viewbinding.caproject.BuildConfig
import iss.nus.edu.sg.viewbinding.caproject.session.SessionManager
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object ApiClient {

    @Volatile
    private var retrofit: Retrofit? = null

    fun authApi(context: Context): AuthApi = retrofit(context).create(AuthApi::class.java)

    fun tripApi(context: Context): TripApi = retrofit(context).create(TripApi::class.java)

    fun agentApi(context: Context): AgentApi = retrofit(context).create(AgentApi::class.java)

    fun bookingApi(context: Context): BookingApi = retrofit(context).create(BookingApi::class.java)

    fun expenseApi(context: Context): ExpenseApi = retrofit(context).create(ExpenseApi::class.java)

    fun userApi(context: Context): UserApi = retrofit(context).create(UserApi::class.java)

    fun mlApi(context: Context): MlApi = retrofit(context).create(MlApi::class.java)

    fun gson(): Gson = GsonBuilder().create()

    private fun retrofit(context: Context): Retrofit {
        return retrofit ?: synchronized(this) {
            retrofit ?: buildRetrofit(context.applicationContext).also { retrofit = it }
        }
    }

    private fun buildRetrofit(context: Context): Retrofit {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(SessionManager(context)))
            .addInterceptor(loggingInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson()))
            .build()
    }
}
