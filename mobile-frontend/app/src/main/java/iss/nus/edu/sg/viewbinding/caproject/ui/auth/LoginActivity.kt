package iss.nus.edu.sg.viewbinding.caproject.ui.auth

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import iss.nus.edu.sg.viewbinding.caproject.R
import iss.nus.edu.sg.viewbinding.caproject.data.repository.AuthRepository
import iss.nus.edu.sg.viewbinding.caproject.databinding.ActivityLoginBinding
import iss.nus.edu.sg.viewbinding.caproject.network.ApiFailureKind
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult
import iss.nus.edu.sg.viewbinding.caproject.ui.main.MainActivity
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var authRepository: AuthRepository

    private val registerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult

        val username = result.data
            ?.getStringExtra(RegisterActivity.EXTRA_REGISTERED_USERNAME)
            .orEmpty()
        binding.usernameInput.setText(username)
        binding.passwordInput.requestFocus()
        Snackbar.make(
            binding.root,
            getString(R.string.account_created_format, username),
            Snackbar.LENGTH_LONG,
        ).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        authRepository = AuthRepository.create(this)
        if (authRepository.currentSession() != null) {
            openMain()
            return
        }

        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.usernameInput.doAfterTextChanged { binding.usernameInputLayout.error = null }
        binding.passwordInput.doAfterTextChanged { binding.passwordInputLayout.error = null }

        binding.loginButton.setOnClickListener { submitLogin() }
        binding.createAccountButton.setOnClickListener {
            registerLauncher.launch(Intent(this, RegisterActivity::class.java))
        }
        binding.forgotPasswordButton.setOnClickListener {
            Snackbar.make(
                binding.root,
                R.string.password_recovery_unavailable,
                Snackbar.LENGTH_LONG,
            ).show()
        }
    }

    private fun submitLogin() {
        val username = binding.usernameInput.text?.toString().orEmpty().trim()
        val password = binding.passwordInput.text?.toString().orEmpty()
        var isValid = true

        binding.usernameInputLayout.error = if (username.isBlank()) {
            isValid = false
            getString(R.string.login_username_required)
        } else {
            null
        }

        binding.passwordInputLayout.error = if (password.isBlank()) {
            isValid = false
            getString(R.string.login_password_required)
        } else {
            null
        }

        if (!isValid) return

        setLoading(true)
        lifecycleScope.launch {
            when (val result = authRepository.login(username, password)) {
                is ApiResult.Success -> openMain()
                is ApiResult.Failure -> {
                    setLoading(false)
                    showLoginFailure(result)
                }
            }
        }
    }

    private fun showLoginFailure(failure: ApiResult.Failure) {
        if (failure.kind == ApiFailureKind.UNAUTHORIZED) {
            binding.passwordInputLayout.error = messageFor(failure)
            return
        }

        val snackbar = Snackbar.make(binding.root, messageFor(failure), Snackbar.LENGTH_LONG)
        if (failure.isRetryable()) snackbar.setAction(R.string.retry) { submitLogin() }
        snackbar.show()
    }

    private fun setLoading(isLoading: Boolean) {
        binding.loginProgress.isVisible = isLoading
        binding.loginButton.isEnabled = !isLoading
        binding.loginButton.setText(
            if (isLoading) R.string.logging_in else R.string.log_in_securely,
        )
        binding.createAccountButton.isEnabled = !isLoading
        binding.usernameInput.isEnabled = !isLoading
        binding.passwordInput.isEnabled = !isLoading
    }

    private fun openMain() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            ),
        )
        finish()
    }
}
