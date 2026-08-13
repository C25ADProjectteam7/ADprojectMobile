package iss.nus.edu.sg.viewbinding.caproject.data.repository

import com.google.gson.JsonParser
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult
import iss.nus.edu.sg.viewbinding.caproject.network.AuthApi
import iss.nus.edu.sg.viewbinding.caproject.network.UserApi
import iss.nus.edu.sg.viewbinding.caproject.network.model.user.ForgotPasswordRequest
import iss.nus.edu.sg.viewbinding.caproject.network.model.user.ResetPasswordRequest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class PasswordRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: PasswordRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        repository = PasswordRepository(
            authApi = retrofit.create(AuthApi::class.java),
            userApi = retrofit.create(UserApi::class.java),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun forgotPasswordSendsRequiredIdentityAndNewPasswordOnly() = runBlocking {
        server.enqueue(jsonResponse("""{"message":"Password updated"}"""))
        val requestModel = ForgotPasswordRequest(
            username = "ashley.tan",
            email = "ashley.tan@company.com.sg",
            department = "Sales",
            phone = "+65 8123 4567",
            newPassword = "newTravel123",
        )

        val result = repository.forgotPassword(requestModel)
        val recordedRequest = server.takeRequest()
        val body = JsonParser.parseString(recordedRequest.body.readUtf8()).asJsonObject

        assertTrue(result is ApiResult.Success)
        assertEquals("POST", recordedRequest.method)
        assertEquals("/api/auth/forgot-password", recordedRequest.path)
        assertEquals("ashley.tan", body.get("username").asString)
        assertEquals("ashley.tan@company.com.sg", body.get("email").asString)
        assertEquals("Sales", body.get("department").asString)
        assertEquals("+65 8123 4567", body.get("phone").asString)
        assertEquals("newTravel123", body.get("newPassword").asString)
        assertFalse(body.has("confirmPassword"))
    }

    @Test
    fun resetPasswordSendsCurrentAndNewPasswordWithoutConfirmation() = runBlocking {
        server.enqueue(
            jsonResponse("""{"code":200,"message":"Password updated","data":null}"""),
        )
        val requestModel = ResetPasswordRequest(
            username = "ashley.tan",
            email = "ashley.tan@company.com.sg",
            department = "Sales",
            phone = "+65 8123 4567",
            oldPassword = "travel123",
            newPassword = "newTravel123",
        )

        val result = repository.resetPassword(requestModel)
        val recordedRequest = server.takeRequest()
        val body = JsonParser.parseString(recordedRequest.body.readUtf8()).asJsonObject

        assertTrue(result is ApiResult.Success)
        assertEquals("PUT", recordedRequest.method)
        assertEquals("/api/users/me/password", recordedRequest.path)
        assertEquals("ashley.tan", body.get("username").asString)
        assertEquals("travel123", body.get("oldPassword").asString)
        assertEquals("newTravel123", body.get("newPassword").asString)
        assertFalse(body.has("confirmPassword"))
    }

    private fun jsonResponse(body: String): MockResponse {
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
    }
}
