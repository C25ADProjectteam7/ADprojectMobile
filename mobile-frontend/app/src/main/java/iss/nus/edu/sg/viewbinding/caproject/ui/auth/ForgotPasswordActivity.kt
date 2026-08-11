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
import iss.nus.edu.sg.viewbinding.caproject.data.repository.PasswordRepository
import iss.nus.edu.sg.viewbinding.caproject.databinding.ActivityForgotPasswordBinding
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult
import iss.nus.edu.sg.viewbinding.caproject.network.model.user.ForgotPasswordRequest
import iss.nus.edu.sg.viewbinding.caproject.validation.InputValidator
import kotlinx.coroutines.launch

class ForgotPasswordActivity : AppCompatActivity() {

    private lateinit var binding: ActivityForgotPasswordBinding
    private lateinit var passwordRepository: PasswordRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        binding = ActivityForgotPasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)
        passwordRepository = PasswordRepository.create(this)

        binding.usernameInput.setText(intent.getStringExtra(EXTRA_INITIAL_USERNAME).orEmpty())

        binding.backButton.setOnClickListener { finish() }
        binding.backToLoginButton.setOnClickListener { finish() }
        binding.submitButton.setOnClickListener { submitForgotPassword() }

        binding.usernameInput.doAfterTextChanged { binding.usernameInputLayout.error = null }
        binding.emailInput.doAfterTextChanged { binding.emailInputLayout.error = null }
        binding.departmentInput.doAfterTextChanged { binding.departmentInputLayout.error = null }
        binding.phoneInput.doAfterTextChanged { binding.phoneInputLayout.error = null }
        binding.newPasswordInput.doAfterTextChanged {
            binding.newPasswordInputLayout.error = null
        }
        binding.confirmPasswordInput.doAfterTextChanged {
            binding.confirmPasswordInputLayout.error = null
        }
    }

    private fun submitForgotPassword() {
        val username = binding.usernameInput.text?.toString().orEmpty().trim()
        val email = binding.emailInput.text?.toString().orEmpty().trim()
        val department = binding.departmentInput.text?.toString().orEmpty().trim()
        val phone = binding.phoneInput.text?.toString().orEmpty().trim()
        val newPassword = binding.newPasswordInput.text?.toString().orEmpty()
        val confirmPassword = binding.confirmPasswordInput.text?.toString().orEmpty()

        if (!validateForm(
                username = username,
                email = email,
                department = department,
                phone = phone,
                newPassword = newPassword,
                confirmPassword = confirmPassword,
            )
        ) {
            return
        }

        val request = ForgotPasswordRequest(
            username = username,
            email = email,
            department = department,
            phone = phone,
            newPassword = newPassword,
        )

        setLoading(true)
        lifecycleScope.launch {
            when (val result = passwordRepository.forgotPassword(request)) {
                is ApiResult.Success -> returnToLogin(username)
                is ApiResult.Failure -> {
                    setLoading(false)
                    showFailure(result)
                }
            }
        }
    }

    private fun validateForm(
        username: String,
        email: String,
        department: String,
        phone: String,
        newPassword: String,
        confirmPassword: String,
    ): Boolean {
        var isValid = true

        binding.usernameInputLayout.error = when {
            username.isBlank() -> getString(R.string.username_required)
            !InputValidator.isValidRegistrationUsername(username) -> {
                getString(R.string.username_invalid)
            }

            else -> null
        }.also { if (it != null) isValid = false }

        binding.emailInputLayout.error = when {
            email.isBlank() -> getString(R.string.email_required)
            !InputValidator.isValidRequiredEmail(email) -> getString(R.string.email_invalid)
            else -> null
        }.also { if (it != null) isValid = false }

        binding.departmentInputLayout.error = if (department.isBlank()) {
            isValid = false
            getString(R.string.department_required)
        } else {
            null
        }

        binding.phoneInputLayout.error = when {
            phone.isBlank() -> getString(R.string.phone_required)
            !InputValidator.isValidPhone(phone) -> getString(R.string.phone_invalid)
            else -> null
        }.also { if (it != null) isValid = false }

        binding.newPasswordInputLayout.error = when {
            newPassword.isBlank() -> getString(R.string.new_password_required)
            !InputValidator.isValidRegistrationPassword(newPassword) -> {
                getString(R.string.new_password_invalid)
            }

            else -> null
        }.also { if (it != null) isValid = false }

        binding.confirmPasswordInputLayout.error = when {
            confirmPassword.isBlank() -> getString(R.string.confirm_new_password_required)
            newPassword != confirmPassword -> getString(R.string.passwords_do_not_match)
            else -> null
        }.also { if (it != null) isValid = false }

        return isValid
    }

    private fun showFailure(failure: ApiResult.Failure) {
        val snackbar = Snackbar.make(
            binding.root,
            passwordMessageFor(failure),
            Snackbar.LENGTH_LONG,
        )
        if (failure.isRetryable()) {
            snackbar.setAction(R.string.retry) { submitForgotPassword() }
        }
        snackbar.show()
    }

    private fun setLoading(isLoading: Boolean) {
        binding.forgotPasswordProgress.isVisible = isLoading
        binding.submitButton.isEnabled = !isLoading
        binding.submitButton.setText(
            if (isLoading) R.string.updating_password else R.string.set_new_password,
        )
        binding.backButton.isEnabled = !isLoading
        binding.backToLoginButton.isEnabled = !isLoading
        binding.usernameInput.isEnabled = !isLoading
        binding.emailInput.isEnabled = !isLoading
        binding.departmentInput.isEnabled = !isLoading
        binding.phoneInput.isEnabled = !isLoading
        binding.newPasswordInput.isEnabled = !isLoading
        binding.confirmPasswordInput.isEnabled = !isLoading
    }

    private fun returnToLogin(username: String) {
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_RESET_USERNAME, username),
        )
        finish()
    }

    companion object {
        const val EXTRA_INITIAL_USERNAME = "initial_username"
        const val EXTRA_RESET_USERNAME = "reset_username"
    }
}
