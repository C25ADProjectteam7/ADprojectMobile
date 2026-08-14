package iss.nus.edu.sg.viewbinding.caproject.data.repository

import com.google.gson.Gson
import com.google.gson.JsonParser
import iss.nus.edu.sg.viewbinding.caproject.network.ApiFailureKind
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult
import iss.nus.edu.sg.viewbinding.caproject.network.UserApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class UserRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: UserRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val gson = Gson()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
        repository = UserRepository(retrofit.create(UserApi::class.java), gson)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun getProfileUsesWrappedMeResponse() = runBlocking {
        server.enqueue(profileResponse())

        val result = repository.getProfile()

        assertTrue(result is ApiResult.Success)
        assertEquals("GET", server.takeRequest().method)
        assertEquals("ashley.tan", (result as ApiResult.Success).value.username)
        assertEquals("TRAVELER", result.value.role)
    }

    @Test
    fun updateProfileSendsOnlyEditableFields() = runBlocking {
        server.enqueue(profileResponse(email = "new@company.com"))

        val result = repository.updateProfile("new@company.com", "Sales", "+65 8123 4567")
        val request = server.takeRequest()
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject

        assertTrue(result is ApiResult.Success)
        assertEquals("PUT", request.method)
        assertEquals("/api/users/me", request.path)
        assertEquals("new@company.com", body.get("email").asString)
        assertEquals("Sales", body.get("department").asString)
        assertEquals("+65 8123 4567", body.get("phone").asString)
        assertEquals(setOf("email", "department", "phone"), body.keySet())
    }

    @Test
    fun invalidWrappedProfileIsRejected() = runBlocking {
        server.enqueue(jsonResponse("""{"code":200,"message":"OK","data":null}"""))

        val result = repository.getProfile()

        assertEquals(ApiFailureKind.INVALID_RESPONSE, (result as ApiResult.Failure).kind)
    }

    private fun profileResponse(email: String = "ashley@company.com"): MockResponse {
        return jsonResponse(
            """{
                "code":200,
                "message":"OK",
                "data":{
                    "id":2,
                    "username":"ashley.tan",
                    "email":"$email",
                    "department":"Sales",
                    "phone":"+65 8123 4567",
                    "role":"TRAVELER"
                }
            }""".trimIndent(),
        )
    }

    private fun jsonResponse(body: String): MockResponse {
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
    }
}
