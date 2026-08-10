package iss.nus.edu.sg.viewbinding.caproject.ui.auth

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import iss.nus.edu.sg.viewbinding.caproject.R
import iss.nus.edu.sg.viewbinding.caproject.data.repository.AuthRepository
import iss.nus.edu.sg.viewbinding.caproject.databinding.ActivityRegisterBinding
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult
import iss.nus.edu.sg.viewbinding.caproject.network.model.RegisterRequest
import iss.nus.edu.sg.viewbinding.caproject.validation.InputValidator
import kotlinx.coroutines.launch

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private lateinit var authRepository: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        authRepository = AuthRepository.create(this)

        binding.backButton.setOnClickListener { finish() }
        binding.backToLoginButton.setOnClickListener { finish() }
        binding.registerButton.setOnClickListener { submitRegistration() }

        binding.usernameInput.doAfterTextChanged { binding.usernameInputLayout.error = null }
        binding.passwordInput.doAfterTextChanged { binding.passwordInputLayout.error = null }
        binding.confirmPasswordInput.doAfterTextChanged {
            binding.confirmPasswordInputLayout.error = null
        }
        binding.emailInput.doAfterTextChanged { binding.emailInputLayout.error = null }
    }

    private fun submitRegistration() {
        val username = binding.usernameInput.text?.toString().orEmpty().trim()
        val password = binding.passwordInput.text?.toString().orEmpty()
        val confirmPassword = binding.confirmPasswordInput.text?.toString().orEmpty()
        val email = binding.emailInput.text?.toString().orEmpty().trim()
        var isValid = true

        binding.usernameInputLayout.error = when {
            username.isBlank() -> {
                isValid = false
                getString(R.string.register_username_required)
            }

            !InputValidator.isValidRegistrationUsername(username) -> {
                isValid = false
                getString(R.string.register_username_invalid)
            }

            else -> null
        }

        binding.passwordInputLayout.error = when {
            password.isBlank() -> {
                isValid = false
                getString(R.string.register_password_required)
            }

            !InputValidator.isValidRegistrationPassword(password) -> {
                isValid = false
                getString(R.string.register_password_invalid)
            }

            else -> null
        }

        binding.confirmPasswordInputLayout.error = when {
            confirmPassword.isBlank() -> {
                isValid = false
                getString(R.string.confirm_password_required)
            }

            password != confirmPassword -> {
                isValid = false
                getString(R.string.passwords_do_not_match)
            }

            else -> null
        }

        binding.emailInputLayout.error = if (!InputValidator.isValidOptionalEmail(email)) {
            isValid = false
            getString(R.string.register_email_invalid)
        } else {
            null
        }

        if (!isValid) return

        val request = RegisterRequest(
            username = username,
            password = password,
            email = email.takeIf { it.isNotBlank() },
            department = binding.departmentInput.text?.toString()?.trim()
                ?.takeIf { it.isNotBlank() },
            phone = binding.phoneInput.text?.toString()?.trim()
                ?.takeIf { it.isNotBlank() },
        )

        setLoading(true)
        lifecycleScope.launch {
            when (val result = authRepository.register(request)) {
                is ApiResult.Success -> returnToLogin(username)
                is ApiResult.Failure -> {
                    setLoading(false)
                    showRegistrationFailure(result)
                }
            }
        }
    }

    private fun showRegistrationFailure(failure: ApiResult.Failure) {
        val snackbar = Snackbar.make(binding.root, messageFor(failure), Snackbar.LENGTH_LONG)
        if (failure.isRetryable()) snackbar.setAction(R.string.retry) { submitRegistration() }
        snackbar.show()
    }

    private fun setLoading(isLoading: Boolean) {
        binding.registerProgress.isVisible = isLoading
        binding.registerButton.isEnabled = !isLoading
        binding.registerButton.setText(
            if (isLoading) R.string.creating_account else R.string.create_account,
        )
        binding.backToLoginButton.isEnabled = !isLoading
        binding.usernameInput.isEnabled = !isLoading
        binding.passwordInput.isEnabled = !isLoading
        binding.confirmPasswordInput.isEnabled = !isLoading
        binding.emailInput.isEnabled = !isLoading
        binding.departmentInput.isEnabled = !isLoading
        binding.phoneInput.isEnabled = !isLoading
    }

    private fun returnToLogin(username: String) {
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_REGISTERED_USERNAME, username),
        )
        finish()
    }

    companion object {
        const val EXTRA_REGISTERED_USERNAME = "registered_username"
    }
}
