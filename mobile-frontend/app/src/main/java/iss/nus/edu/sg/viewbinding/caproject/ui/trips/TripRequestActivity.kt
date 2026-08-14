package iss.nus.edu.sg.viewbinding.caproject.ui.trips

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import iss.nus.edu.sg.viewbinding.caproject.R
import iss.nus.edu.sg.viewbinding.caproject.data.local.CurrentTripStore
import iss.nus.edu.sg.viewbinding.caproject.data.repository.TripRepository
import iss.nus.edu.sg.viewbinding.caproject.databinding.ActivityTripRequestBinding
import iss.nus.edu.sg.viewbinding.caproject.model.TripRequestData
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult
import iss.nus.edu.sg.viewbinding.caproject.ui.auth.AuthenticatedActivity
import iss.nus.edu.sg.viewbinding.caproject.ui.main.MainActivity
import iss.nus.edu.sg.viewbinding.caproject.validation.InputValidator
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

class TripRequestActivity : AuthenticatedActivity() {

    private lateinit var binding: ActivityTripRequestBinding
    private lateinit var tripRepository: TripRepository
    private val dateFormatter = DateTimeFormatter.ofPattern("dd MMM uuuu", Locale.ENGLISH)
    // Rolling defaults a week out: flight/hotel providers (Duffel, LiteAPI)
    // only offer inventory for today-or-later dates, so a fixed date like
    // 2026-08-12 silently stops working once it falls into the past.
    private var startDate: LocalDate? = LocalDate.now().plusDays(DEFAULT_LEAD_DAYS)
    private var endDate: LocalDate? = LocalDate.now().plusDays(DEFAULT_LEAD_DAYS + 2)
    private var editingTrip: TripRequestData? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        binding = ActivityTripRequestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        tripRepository = TripRepository.create(this)
        editingTrip = IntentCompat.getSerializableExtra(
            intent,
            EXTRA_EDIT_TRIP,
            TripRequestData::class.java,
        )

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

        // Prefill the rolling default dates so the traveler always sees what
        // will be sent to the Agent (editing an existing trip overwrites both).
        binding.startDateInput.setText(startDate?.format(dateFormatter))
        binding.endDateInput.setText(endDate?.format(dateFormatter))

        editingTrip?.let(::bindEditingTrip)
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
        val today = LocalDate.now()
        val date = initialDate ?: today
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                onDateSelected(LocalDate.of(year, month + 1, dayOfMonth))
            },
            date.year,
            date.monthValue - 1,
            date.dayOfMonth,
        ).apply {
            // Providers have no inventory for past dates - block them in the
            // picker instead of letting the Agent fail later.
            datePicker.minDate = today.atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli()
        }.show()
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

        binding.startDateInputLayout.error = when {
            startDate == null -> {
                isValid = false
                getString(R.string.start_date_required)
            }

            !InputValidator.isStartDateTodayOrLater(startDate) -> {
                isValid = false
                getString(R.string.start_date_past)
            }

            else -> null
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

        val localRequest = TripRequestData(
            destination = destination,
            startDate = requireNotNull(startDate),
            endDate = requireNotNull(endDate),
            budget = budget.replace(",", "").toDouble(),
            preferences = selectedPreferences(),
            notes = binding.notesInput.text?.toString().orEmpty().trim(),
            remoteId = editingTrip?.remoteId,
            remoteTitle = editingTrip?.remoteTitle.orEmpty(),
            remoteStatus = editingTrip?.remoteStatus.orEmpty(),
        )

        setLoading(true)
        lifecycleScope.launch {
            val result = editingTrip?.remoteId?.let { tripId ->
                tripRepository.updateTrip(tripId, localRequest)
            } ?: tripRepository.createTrip(localRequest)

            when (result) {
                is ApiResult.Success -> handleSavedTrip(result.value)
                is ApiResult.Failure -> {
                    setLoading(false)
                    showSaveFailure(result)
                }
            }
        }
    }

    private fun handleSavedTrip(trip: TripRequestData) {
        CurrentTripStore.saveRequest(trip)
        if (editingTrip != null) {
            setResult(RESULT_OK)
            finish()
            return
        }
        startActivity(ItineraryReviewActivity.createIntent(this, trip))
        finish()
    }

    private fun showSaveFailure(failure: ApiResult.Failure) {
        val snackbar = Snackbar.make(binding.root, tripMessageFor(failure), Snackbar.LENGTH_LONG)
        if (failure.isTripRetryable()) snackbar.setAction(R.string.retry) { submitTripRequest() }
        snackbar.show()
    }

    private fun setLoading(isLoading: Boolean) {
        binding.requestProgress.isVisible = isLoading
        binding.sendToAgentButton.isEnabled = !isLoading
        binding.sendToAgentButton.setText(
            when {
                isLoading -> R.string.saving_trip
                editingTrip != null -> R.string.save_trip_changes
                else -> R.string.send_to_agent
            },
        )
        binding.tripRequestScroll.isEnabled = !isLoading
    }

    private fun bindEditingTrip(trip: TripRequestData) {
        binding.tripRequestTitle.setText(R.string.edit_trip)
        binding.tripRequestSubtitle.setText(R.string.edit_trip_subtitle)
        startDate = trip.startDate
        endDate = trip.endDate
        binding.destinationInput.setText(trip.destination)
        binding.startDateInput.setText(trip.startDate.format(dateFormatter))
        binding.endDateInput.setText(trip.endDate.format(dateFormatter))
        binding.budgetInput.setText(trip.budget.toBigDecimal().stripTrailingZeros().toPlainString())
        binding.notesInput.setText(trip.notes)
        binding.cityCentreChip.isChecked = getString(R.string.preference_city_centre) in trip.preferences
        binding.directFlightsChip.isChecked = getString(R.string.preference_direct_flights) in trip.preferences
        binding.familyFriendlyChip.isChecked = getString(R.string.preference_family_friendly) in trip.preferences
        binding.businessHotelChip.isChecked = getString(R.string.preference_business_hotel) in trip.preferences
        binding.sendToAgentButton.setText(R.string.save_trip_changes)
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

    companion object {
        private const val EXTRA_EDIT_TRIP = "edit_trip"
        // How many days after today the default trip starts (end = +2 nights).
        private const val DEFAULT_LEAD_DAYS = 7L

        fun createEditIntent(context: Context, trip: TripRequestData): Intent {
            return Intent(context, TripRequestActivity::class.java).putExtra(EXTRA_EDIT_TRIP, trip)
        }
    }
}
