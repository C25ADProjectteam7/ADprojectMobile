package iss.nus.edu.sg.viewbinding.caproject.ui.profile

import android.content.Intent
import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import iss.nus.edu.sg.viewbinding.caproject.R
import iss.nus.edu.sg.viewbinding.caproject.data.repository.AuthRepository
import iss.nus.edu.sg.viewbinding.caproject.data.repository.UserRepository
import iss.nus.edu.sg.viewbinding.caproject.databinding.ActivityProfileBinding
import iss.nus.edu.sg.viewbinding.caproject.model.UserProfile
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult
import iss.nus.edu.sg.viewbinding.caproject.ui.auth.AuthenticatedActivity
import iss.nus.edu.sg.viewbinding.caproject.ui.auth.LoginActivity
import iss.nus.edu.sg.viewbinding.caproject.ui.auth.ResetPasswordActivity
import iss.nus.edu.sg.viewbinding.caproject.validation.InputValidator
import kotlinx.coroutines.launch

class ProfileActivity : AuthenticatedActivity() {

    private lateinit var binding: ActivityProfileBinding
    private lateinit var userRepository: UserRepository
    private lateinit var authRepository: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        userRepository = UserRepository.create(this)
        authRepository = AuthRepository.create(this)

        binding.backButton.setOnClickListener { finish() }
        binding.profileRetryButton.setOnClickListener { loadProfile() }
        binding.saveProfileButton.setOnClickListener { validateAndSave() }
        binding.resetPasswordButton.setOnClickListener {
            startActivity(Intent(this, ResetPasswordActivity::class.java))
        }
        binding.logoutButton.setOnClickListener { confirmLogout() }
        binding.emailInput.doAfterTextChanged { binding.emailInputLayout.error = null }
        binding.phoneInput.doAfterTextChanged { binding.phoneInputLayout.error = null }
        loadProfile()
    }

    private fun loadProfile() {
        binding.profileLoading.isVisible = true
        binding.profileContent.isVisible = false
        binding.profileStateContainer.isVisible = false
        lifecycleScope.launch {
            when (val result = userRepository.getProfile()) {
                is ApiResult.Success -> bindProfile(result.value)
                is ApiResult.Failure -> showFailure(result)
            }
        }
    }

    private fun bindProfile(profile: UserProfile) {
        binding.profileLoading.isVisible = false
        binding.profileStateContainer.isVisible = false
        binding.profileContent.isVisible = true
        binding.usernameInput.setText(profile.username)
        binding.roleInput.setText(profile.role)
        binding.emailInput.setText(profile.email)
        binding.departmentInput.setText(profile.department)
        binding.phoneInput.setText(profile.phone)
    }

    private fun validateAndSave() {
        val email = binding.emailInput.text?.toString().orEmpty().trim()
        val department = binding.departmentInput.text?.toString().orEmpty().trim()
        val phone = binding.phoneInput.text?.toString().orEmpty().trim()
        var valid = true
        binding.emailInputLayout.error = if (!InputValidator.isValidOptionalEmail(email)) {
            valid = false
            getString(R.string.email_invalid)
        } else null
        binding.phoneInputLayout.error = if (phone.isNotBlank() && !InputValidator.isValidPhone(phone)) {
            valid = false
            getString(R.string.phone_invalid)
        } else null
        if (!valid) return

        setSaving(true)
        lifecycleScope.launch {
            when (val result = userRepository.updateProfile(email, department, phone)) {
                is ApiResult.Success -> {
                    bindProfile(result.value)
                    setSaving(false)
                    Snackbar.make(binding.root, R.string.profile_updated, Snackbar.LENGTH_SHORT).show()
                }
                is ApiResult.Failure -> {
                    setSaving(false)
                    val snackbar = Snackbar.make(
                        binding.root,
                        profileMessageFor(result),
                        Snackbar.LENGTH_LONG,
                    )
                    if (result.isProfileRetryable()) {
                        snackbar.setAction(R.string.retry) { validateAndSave() }
                    }
                    snackbar.show()
                }
            }
        }
    }

    private fun setSaving(saving: Boolean) {
        binding.saveProgress.isVisible = saving
        binding.saveProfileButton.isEnabled = !saving
        binding.resetPasswordButton.isEnabled = !saving
        binding.saveProfileButton.setText(
            if (saving) R.string.saving_profile else R.string.save_profile,
        )
    }

    private fun showFailure(failure: ApiResult.Failure) {
        binding.profileLoading.isVisible = false
        binding.profileContent.isVisible = false
        binding.profileStateContainer.isVisible = true
        binding.profileStateMessage.text = profileMessageFor(failure)
        binding.profileRetryButton.isVisible = failure.isProfileRetryable()
    }

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.logout)
            .setMessage(R.string.logout_confirmation)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.logout) { _, _ -> logout() }
            .show()
    }

    private fun logout() {
        authRepository.logout()
        startActivity(
            Intent(this, LoginActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            ),
        )
    }
}
