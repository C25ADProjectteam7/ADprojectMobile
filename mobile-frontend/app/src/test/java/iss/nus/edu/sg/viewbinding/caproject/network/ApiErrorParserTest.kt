package iss.nus.edu.sg.viewbinding.caproject.network

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiErrorParserTest {

    private val gson = Gson()

    @Test
    fun unauthorizedResponse_keepsBackendMessage() {
        val failure = ApiErrorParser.fromHttp(
            statusCode = 401,
            rawBody = """{"code":401,"message":"Invalid username or password","data":null}""",
            gson = gson,
        )

        assertEquals(ApiFailureKind.UNAUTHORIZED, failure.kind)
        assertEquals("Invalid username or password", failure.message)
        assertEquals(401, failure.statusCode)
        assertEquals(401, failure.backendCode)
    }

    @Test
    fun invalidErrorBody_fallsBackWithoutCrashing() {
        val failure = ApiErrorParser.fromHttp(
            statusCode = 500,
            rawBody = "not-json",
            gson = gson,
        )

        assertEquals(ApiFailureKind.SERVER, failure.kind)
        assertEquals(null, failure.message)
        assertEquals(500, failure.statusCode)
        assertEquals(null, failure.backendCode)
    }

    @Test
    fun commonClientStatusesAreClassifiedSeparately() {
        assertEquals(
            ApiFailureKind.FORBIDDEN,
            ApiErrorParser.fromHttp(403, null, gson).kind,
        )
        assertEquals(
            ApiFailureKind.NOT_FOUND,
            ApiErrorParser.fromHttp(404, null, gson).kind,
        )
        assertEquals(
            ApiFailureKind.CONFLICT,
            ApiErrorParser.fromHttp(409, null, gson).kind,
        )
        assertEquals(
            ApiFailureKind.VALIDATION,
            ApiErrorParser.fromHttp(422, null, gson).kind,
        )
    }
}
