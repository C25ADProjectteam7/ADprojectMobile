package iss.nus.edu.sg.viewbinding.caproject.ui.auth

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.snackbar.Snackbar
import iss.nus.edu.sg.viewbinding.caproject.R
import iss.nus.edu.sg.viewbinding.caproject.databinding.ActivityLoginBinding
import iss.nus.edu.sg.viewbinding.caproject.ui.main.MainActivity
import iss.nus.edu.sg.viewbinding.caproject.validation.InputValidator

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.emailInput.doAfterTextChanged { binding.emailInputLayout.error = null }
        binding.passwordInput.doAfterTextChanged { binding.passwordInputLayout.error = null }

        binding.loginButton.setOnClickListener { submitLogin() }
        binding.forgotPasswordButton.setOnClickListener {
            Snackbar.make(
                binding.root,
                R.string.password_recovery_unavailable,
                Snackbar.LENGTH_LONG,
            ).show()
        }
    }

    private fun submitLogin() {
        val email = binding.emailInput.text?.toString().orEmpty().trim()
        val password = binding.passwordInput.text?.toString().orEmpty()
        var isValid = true

        binding.emailInputLayout.error = when {
            email.isBlank() -> {
                isValid = false
                getString(R.string.login_email_required)
            }

            !InputValidator.isValidEmail(email) -> {
                isValid = false
                getString(R.string.login_email_invalid)
            }

            else -> null
        }

        binding.passwordInputLayout.error = if (password.isBlank()) {
            isValid = false
            getString(R.string.login_password_required)
        } else {
            null
        }

        if (!isValid) return

        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
