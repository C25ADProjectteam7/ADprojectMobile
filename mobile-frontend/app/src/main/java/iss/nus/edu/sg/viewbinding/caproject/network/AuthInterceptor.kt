package iss.nus.edu.sg.viewbinding.caproject.network

import iss.nus.edu.sg.viewbinding.caproject.session.SessionManager
import iss.nus.edu.sg.viewbinding.caproject.session.SessionEventBus
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor internal constructor(
    private val authorizationHeaderProvider: () -> String?,
    private val onUnauthorized: () -> Unit,
) : Interceptor {

    constructor(sessionManager: SessionManager) : this(
        authorizationHeaderProvider = sessionManager::authorizationHeader,
        onUnauthorized = {
            sessionManager.clear()
            SessionEventBus.notifyExpired()
        },
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val authorizationHeader = authorizationHeaderProvider()

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
            if (response.code == HTTP_UNAUTHORIZED) onUnauthorized()
        }
    }

    private companion object {
        const val AUTHORIZATION = "Authorization"
        const val HTTP_UNAUTHORIZED = 401
    }
}
