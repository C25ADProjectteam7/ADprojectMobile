package iss.nus.edu.sg.viewbinding.caproject.ui.auth

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import iss.nus.edu.sg.viewbinding.caproject.session.SessionEventBus
import iss.nus.edu.sg.viewbinding.caproject.session.SessionManager
import kotlinx.coroutines.launch

abstract class AuthenticatedActivity : AppCompatActivity() {

    private val sessionManager by lazy(LazyThreadSafetyMode.NONE) {
        SessionManager(applicationContext)
    }
    private var isRedirectingToLogin = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SessionEventBus.events.collect { redirectToLogin() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (sessionManager.currentSession() == null) redirectToLogin()
    }

    private fun redirectToLogin() {
        if (isRedirectingToLogin) return
        isRedirectingToLogin = true
        startActivity(
            Intent(this, LoginActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            ),
        )
        finish()
    }
}
