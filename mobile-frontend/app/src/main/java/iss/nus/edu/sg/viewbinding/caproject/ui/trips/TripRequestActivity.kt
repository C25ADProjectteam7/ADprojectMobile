package iss.nus.edu.sg.viewbinding.caproject.ui.trips

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import iss.nus.edu.sg.viewbinding.caproject.R
import iss.nus.edu.sg.viewbinding.caproject.data.mock.CurrentTripStore
import iss.nus.edu.sg.viewbinding.caproject.databinding.ActivityTripRequestBinding
import iss.nus.edu.sg.viewbinding.caproject.model.TripRequestData
import iss.nus.edu.sg.viewbinding.caproject.ui.main.MainActivity
import iss.nus.edu.sg.viewbinding.caproject.validation.InputValidator
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class TripRequestActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTripRequestBinding
    private val dateFormatter = DateTimeFormatter.ofPattern("dd MMM uuuu", Locale.ENGLISH)
    private var startDate: LocalDate? = LocalDate.of(2026, 8, 12)
    private var endDate: LocalDate? = LocalDate.of(2026, 8, 14)

    private val finishMockRequest = Runnable {
        binding.requestProgress.isVisible = false
        binding.sendToAgentButton.isEnabled = true
        binding.sendToAgentButton.setText(R.string.send_to_agent)
        openItineraryReview()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        binding = ActivityTripRequestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.startDateInput.setOnClickListener {
            showDatePicker(startDate) { selectedDate ->
                startDate = selectedDate
                binding.startDateInput.setText(selectedDate.format(dateFormatter))
                binding.startDateInputLayout.error = null
                binding.endDateInputLayout.error = null
            }
        }
        binding.endDateInput.setOnClickListener {
            showDatePicker(endDate) { selectedDate ->
                endDate = selectedDate
                binding.endDateInput.setText(selectedDate.format(dateFormatter))
                binding.endDateInputLayout.error = null
            }
        }

        binding.destinationInput.doAfterTextChanged {
            binding.destinationInputLayout.error = null
        }
        binding.budgetInput.doAfterTextChanged {
            binding.budgetInputLayout.error = null
        }
        binding.sendToAgentButton.setOnClickListener { submitTripRequest() }
        binding.tripRequestRoot.setOnClickListener { hideKeyboard() }

        setupBottomNavigation()
    }

    private fun setupBottomNavigation() {
        binding.tripRequestBottomNavigation.selectedItemId = R.id.navigation_trips
        binding.tripRequestBottomNavigation.setOnItemSelectedListener { item ->
            if (item.itemId == R.id.navigation_trips) {
                finish()
            } else {
                openMainTab(item.itemId)
            }
            true
        }
        binding.tripRequestBottomNavigation.setOnItemReselectedListener { item ->
            if (item.itemId == R.id.navigation_trips) finish()
        }
    }

    private fun openMainTab(itemId: Int) {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_SELECTED_TAB, itemId)
            },
        )
        finish()
    }

    private fun showDatePicker(initialDate: LocalDate?, onDateSelected: (LocalDate) -> Unit) {
        val date = initialDate ?: LocalDate.now()
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                onDateSelected(LocalDate.of(year, month + 1, dayOfMonth))
            },
            date.year,
            date.monthValue - 1,
            date.dayOfMonth,
        ).show()
    }

    private fun submitTripRequest() {
        hideKeyboard()

        val destination = binding.destinationInput.text?.toString().orEmpty().trim()
        val budget = binding.budgetInput.text?.toString().orEmpty()
        var isValid = true

        binding.destinationInputLayout.error = if (destination.isBlank()) {
            isValid = false
            getString(R.string.destination_required)
        } else {
            null
        }

        binding.startDateInputLayout.error = if (startDate == null) {
            isValid = false
            getString(R.string.start_date_required)
        } else {
            null
        }

        binding.endDateInputLayout.error = when {
            endDate == null -> {
                isValid = false
                getString(R.string.end_date_required)
            }

            !InputValidator.isValidDateRange(startDate, endDate) -> {
                isValid = false
                getString(R.string.end_date_invalid)
            }

            else -> null
        }

        binding.budgetInputLayout.error = when {
            budget.isBlank() -> {
                isValid = false
                getString(R.string.budget_required)
            }

            !InputValidator.isPositiveBudget(budget) -> {
                isValid = false
                getString(R.string.budget_invalid)
            }

            else -> null
        }

        if (!isValid) return

        binding.requestProgress.isVisible = true
        binding.sendToAgentButton.isEnabled = false
        binding.sendToAgentButton.setText(R.string.sending_to_agent)
        binding.sendToAgentButton.postDelayed(finishMockRequest, MOCK_REQUEST_DELAY_MS)
    }

    private fun openItineraryReview() {
        val tripRequest = TripRequestData(
            destination = binding.destinationInput.text?.toString().orEmpty().trim(),
            startDate = requireNotNull(startDate),
            endDate = requireNotNull(endDate),
            budget = binding.budgetInput.text?.toString().orEmpty()
                .replace(",", "")
                .toDoubleOrNull()
                ?: DEFAULT_BUDGET,
            preferences = selectedPreferences(),
            notes = binding.notesInput.text?.toString().orEmpty().trim(),
        )
        CurrentTripStore.saveRequest(tripRequest)

        startActivity(
            ItineraryReviewActivity.createIntent(
                context = this,
                tripRequest = tripRequest,
            ),
        )
    }

    private fun selectedPreferences(): ArrayList<String> {
        return arrayListOf<String>().apply {
            if (binding.cityCentreChip.isChecked) add(binding.cityCentreChip.text.toString())
            if (binding.directFlightsChip.isChecked) add(binding.directFlightsChip.text.toString())
            if (binding.familyFriendlyChip.isChecked) add(binding.familyFriendlyChip.text.toString())
            if (binding.businessHotelChip.isChecked) add(binding.businessHotelChip.text.toString())
        }
    }

    private fun hideKeyboard() {
        currentFocus?.let { focusedView ->
            val keyboard = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            keyboard.hideSoftInputFromWindow(focusedView.windowToken, 0)
            focusedView.clearFocus()
        }
    }

    override fun onDestroy() {
        if (::binding.isInitialized) {
            binding.sendToAgentButton.removeCallbacks(finishMockRequest)
        }
        super.onDestroy()
    }

    companion object {
        private const val MOCK_REQUEST_DELAY_MS = 900L
        private const val DEFAULT_BUDGET = 2_000.0
    }
}
