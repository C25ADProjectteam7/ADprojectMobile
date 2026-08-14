package iss.nus.edu.sg.viewbinding.caproject.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthInterceptorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun validSessionAddsBearerHeader() {
        server.enqueue(MockResponse().setResponseCode(200))
        val client = clientWith(header = "Bearer test-token")

        client.newCall(Request.Builder().url(server.url("/api/trips")).build()).execute().close()

        assertEquals("Bearer test-token", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun absentSessionDoesNotAddAuthorizationHeader() {
        server.enqueue(MockResponse().setResponseCode(200))
        val client = clientWith(header = null)

        client.newCall(Request.Builder().url(server.url("/api/trips")).build()).execute().close()

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun unauthorizedResponseInvokesSessionExpiryCallback() {
        server.enqueue(MockResponse().setResponseCode(401))
        var unauthorizedHandled = false
        val interceptor = AuthInterceptor(
            authorizationHeaderProvider = { "Bearer expired-token" },
            onUnauthorized = { unauthorizedHandled = true },
        )
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()

        assertFalse(unauthorizedHandled)
        client.newCall(Request.Builder().url(server.url("/api/trips")).build()).execute().close()

        assertTrue(unauthorizedHandled)
    }

    private fun clientWith(header: String?): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(
                AuthInterceptor(
                    authorizationHeaderProvider = { header },
                    onUnauthorized = {},
                ),
            )
            .build()
    }
}
