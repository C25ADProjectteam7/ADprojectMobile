package iss.nus.edu.sg.viewbinding.caproject.network

import iss.nus.edu.sg.viewbinding.caproject.session.SessionManager
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(
    private val sessionManager: SessionManager,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val authorizationHeader = sessionManager.authorizationHeader()

        if (authorizationHeader == null || originalRequest.header(AUTHORIZATION) != null) {
            return proceedAndHandleUnauthorized(chain, originalRequest)
        }

        val authenticatedRequest = originalRequest.newBuilder()
            .header(AUTHORIZATION, authorizationHeader)
            .build()
        return proceedAndHandleUnauthorized(chain, authenticatedRequest)
    }

    private fun proceedAndHandleUnauthorized(
        chain: Interceptor.Chain,
        request: okhttp3.Request,
    ): Response {
        return chain.proceed(request).also { response ->
            if (response.code == HTTP_UNAUTHORIZED) sessionManager.clear()
        }
    }

    private companion object {
        const val AUTHORIZATION = "Authorization"
        const val HTTP_UNAUTHORIZED = 401
    }
}
