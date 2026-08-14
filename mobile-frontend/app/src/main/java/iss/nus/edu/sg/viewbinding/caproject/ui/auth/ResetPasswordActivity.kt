package iss.nus.edu.sg.viewbinding.caproject.ui.auth

import android.content.Intent
import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import iss.nus.edu.sg.viewbinding.caproject.R
import iss.nus.edu.sg.viewbinding.caproject.data.repository.PasswordRepository
import iss.nus.edu.sg.viewbinding.caproject.databinding.ActivityResetPasswordBinding
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult
import iss.nus.edu.sg.viewbinding.caproject.network.model.user.ResetPasswordRequest
import iss.nus.edu.sg.viewbinding.caproject.session.SessionManager
import iss.nus.edu.sg.viewbinding.caproject.validation.InputValidator
import kotlinx.coroutines.launch

class ResetPasswordActivity : AuthenticatedActivity() {

    private lateinit var binding: ActivityResetPasswordBinding
    private lateinit var passwordRepository: PasswordRepository
    private lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        binding = ActivityResetPasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)
        passwordRepository = PasswordRepository.create(this)
        sessionManager = SessionManager(applicationContext)

        binding.usernameInput.setText(sessionManager.currentSession()?.username.orEmpty())
        binding.backButton.setOnClickListener { finish() }
        binding.submitButton.setOnClickListener { submitResetPassword() }

        binding.usernameInput.doAfterTextChanged { binding.usernameInputLayout.error = null }
        binding.emailInput.doAfterTextChanged { binding.emailInputLayout.error = null }
        binding.departmentInput.doAfterTextChanged { binding.departmentInputLayout.error = null }
        binding.phoneInput.doAfterTextChanged { binding.phoneInputLayout.error = null }
        binding.currentPasswordInput.doAfterTextChanged {
            binding.currentPasswordInputLayout.error = null
        }
        binding.newPasswordInput.doAfterTextChanged {
            binding.newPasswordInputLayout.error = null
        }
        binding.confirmPasswordInput.doAfterTextChanged {
            binding.confirmPasswordInputLayout.error = null
        }
    }

    private fun submitResetPassword() {
        val username = binding.usernameInput.text?.toString().orEmpty().trim()
        val email = binding.emailInput.text?.toString().orEmpty().trim()
        val department = binding.departmentInput.text?.toString().orEmpty().trim()
        val phone = binding.phoneInput.text?.toString().orEmpty().trim()
        val currentPassword = binding.currentPasswordInput.text?.toString().orEmpty()
        val newPassword = binding.newPasswordInput.text?.toString().orEmpty()
        val confirmPassword = binding.confirmPasswordInput.text?.toString().orEmpty()

        if (!validateForm(
                username = username,
                email = email,
                department = department,
                phone = phone,
                currentPassword = currentPassword,
                newPassword = newPassword,
                confirmPassword = confirmPassword,
            )
        ) {
            return
        }

        val request = ResetPasswordRequest(
            username = username,
            email = email,
            department = department,
            phone = phone,
            oldPassword = currentPassword,
            newPassword = newPassword,
        )

        setLoading(true)
        lifecycleScope.launch {
            when (val result = passwordRepository.resetPassword(request)) {
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
        currentPassword: String,
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

        binding.currentPasswordInputLayout.error = if (currentPassword.isBlank()) {
            isValid = false
            getString(R.string.current_password_required)
        } else {
            null
        }

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
            snackbar.setAction(R.string.retry) { submitResetPassword() }
        }
        snackbar.show()
    }

    private fun setLoading(isLoading: Boolean) {
        binding.resetPasswordProgress.isVisible = isLoading
        binding.submitButton.isEnabled = !isLoading
        binding.submitButton.setText(
            if (isLoading) R.string.updating_password else R.string.reset_password,
        )
        binding.backButton.isEnabled = !isLoading
        binding.usernameInput.isEnabled = !isLoading
        binding.emailInput.isEnabled = !isLoading
        binding.departmentInput.isEnabled = !isLoading
        binding.phoneInput.isEnabled = !isLoading
        binding.currentPasswordInput.isEnabled = !isLoading
        binding.newPasswordInput.isEnabled = !isLoading
        binding.confirmPasswordInput.isEnabled = !isLoading
    }

    private fun returnToLogin(username: String) {
        sessionManager.clear()
        startActivity(
            Intent(this, LoginActivity::class.java)
                .putExtra(LoginActivity.EXTRA_PASSWORD_UPDATED_USERNAME, username)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
    }
}
