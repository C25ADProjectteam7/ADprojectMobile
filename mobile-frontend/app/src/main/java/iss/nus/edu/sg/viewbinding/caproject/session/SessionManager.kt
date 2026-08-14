package iss.nus.edu.sg.viewbinding.caproject.session

import android.content.Context
import iss.nus.edu.sg.viewbinding.caproject.network.model.LoginResponse

class SessionManager(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun save(loginResponse: LoginResponse) {
        val expiresAt = System.currentTimeMillis() + loginResponse.expiresIn
        preferences.edit()
            .putString(KEY_ACCESS_TOKEN, loginResponse.accessToken)
            .putString(KEY_TOKEN_TYPE, loginResponse.tokenType)
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .putLong(KEY_USER_ID, loginResponse.userId)
            .putString(KEY_USERNAME, loginResponse.username)
            .putString(KEY_ROLE, loginResponse.role)
            .apply()
    }

    fun currentSession(): UserSession? {
        val accessToken = preferences.getString(KEY_ACCESS_TOKEN, null) ?: return null
        val expiresAt = preferences.getLong(KEY_EXPIRES_AT, 0L)
        if (expiresAt <= System.currentTimeMillis()) {
            clear()
            return null
        }

        return UserSession(
            accessToken = accessToken,
            tokenType = preferences.getString(KEY_TOKEN_TYPE, DEFAULT_TOKEN_TYPE)
                ?: DEFAULT_TOKEN_TYPE,
            expiresAtEpochMillis = expiresAt,
            userId = preferences.getLong(KEY_USER_ID, 0L),
            username = preferences.getString(KEY_USERNAME, "").orEmpty(),
            role = preferences.getString(KEY_ROLE, "").orEmpty(),
        )
    }

    fun authorizationHeader(): String? {
        val session = currentSession() ?: return null
        return "${session.tokenType} ${session.accessToken}"
    }

    fun isLoggedIn(): Boolean = currentSession() != null

    fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "travelhub_session"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_TOKEN_TYPE = "token_type"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_USER_ID = "user_id"
        const val KEY_USERNAME = "username"
        const val KEY_ROLE = "role"
        const val DEFAULT_TOKEN_TYPE = "Bearer"
    }
}
