package iss.nus.edu.sg.viewbinding.caproject.ui.trips

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import com.google.android.material.snackbar.Snackbar
import iss.nus.edu.sg.viewbinding.caproject.R
import iss.nus.edu.sg.viewbinding.caproject.data.mock.CurrentTripStore
import iss.nus.edu.sg.viewbinding.caproject.data.mock.MockTravelData
import iss.nus.edu.sg.viewbinding.caproject.databinding.ActivityItineraryReviewBinding
import iss.nus.edu.sg.viewbinding.caproject.model.TripRequestData
import iss.nus.edu.sg.viewbinding.caproject.ui.main.MainActivity
import iss.nus.edu.sg.viewbinding.caproject.validation.BudgetCalculator
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale

class ItineraryReviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityItineraryReviewBinding
    private lateinit var tripRequest: TripRequestData

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        binding = ActivityItineraryReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindItinerary()
        setupActions()
        setupBottomNavigation()
    }

    private fun bindItinerary() {
        tripRequest = IntentCompat.getSerializableExtra(
            intent,
            TripRequestData.EXTRA_KEY,
            TripRequestData::class.java,
        ) ?: CurrentTripStore.currentTrip
        val itinerary = MockTravelData.itineraryFor(tripRequest)

        binding.generatedSummary.text = getString(
            R.string.itinerary_generated_format,
            tripRequest.city,
            tripRequest.tripDays,
        )
        val preferences = tripRequest.preferences
            .takeIf { it.isNotEmpty() }
            ?.joinToString()
            ?: getString(R.string.no_preferences_selected)
        val notes = tripRequest.notes
            .takeIf { it.isNotBlank() }
            ?.let { getString(R.string.itinerary_notes_format, it) }
            .orEmpty()
        binding.agentMessage.text = getString(
            R.string.itinerary_agent_message_format,
            tripRequest.city,
            formatDateRange(),
            preferences,
            notes,
        )
        binding.flightRoute.text = itinerary.flightRoute
        binding.flightSummary.text = itinerary.flightSummary
        binding.flightSchedule.text = itinerary.flightSchedule
        binding.flightNumber.text = itinerary.flightNumber
        binding.flightPrice.text = formatSgd(itinerary.flightPrice)
        binding.hotelName.text = itinerary.hotelName
        binding.hotelSummary.text = itinerary.hotelSummary
        binding.hotelPrediction.text = itinerary.hotelPrediction
        binding.hotelStay.text = itinerary.hotelStay
        binding.hotelRate.text = itinerary.hotelRate
        binding.hotelPrice.text = formatSgd(itinerary.hotelPrice)
        binding.transferTitle.text = itinerary.transferTitle
        binding.transferDescription.text = itinerary.transferDescription
        binding.transferDuration.text = itinerary.transferDuration
        binding.budgetTotal.text = getString(
            R.string.budget_total_format,
            formatSgd(itinerary.estimatedTotal),
            formatSgd(tripRequest.budget),
        )
        binding.budgetProgress.progress = BudgetCalculator.progressPercent(
            itinerary.estimatedTotal,
            tripRequest.budget,
        )
    }

    private fun setupActions() {
        binding.backButton.setOnClickListener { finish() }
        binding.requestChangesButton.setOnClickListener {
            Snackbar.make(
                binding.itineraryRoot,
                R.string.agent_changes_later,
                Snackbar.LENGTH_LONG,
            ).setAnchorView(binding.itineraryBottomNavigation).show()
        }
        binding.confirmMockBookingButton.setOnClickListener {
            startActivity(
                MockBookingConfirmationActivity.createIntent(
                    this,
                    tripRequest,
                ),
            )
        }
    }

    private fun setupBottomNavigation() {
        binding.itineraryBottomNavigation.selectedItemId = R.id.navigation_trips
        binding.itineraryBottomNavigation.setOnItemSelectedListener { item ->
            if (item.itemId == R.id.navigation_trips) {
                openMainTab(R.id.navigation_trips)
            } else {
                openMainTab(item.itemId)
            }
            true
        }
        binding.itineraryBottomNavigation.setOnItemReselectedListener {
            openMainTab(R.id.navigation_trips)
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

    private fun formatSgd(value: Double): String {
        val formatter = NumberFormat.getNumberInstance(Locale.ENGLISH).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 2
        }
        return "S$${formatter.format(value)}"
    }

    private fun formatDateRange(): String {
        val startDate = tripRequest.startDate
        val endDate = tripRequest.endDate
        val monthYear = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)
        if (startDate.month == endDate.month && startDate.year == endDate.year) {
            return "${startDate.dayOfMonth}–${endDate.dayOfMonth} ${endDate.format(monthYear)}"
        }
        val fullDate = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
        return "${startDate.format(fullDate)}–${endDate.format(fullDate)}"
    }

    companion object {
        fun createIntent(
            context: Context,
            tripRequest: TripRequestData,
        ): Intent {
            return Intent(context, ItineraryReviewActivity::class.java).apply {
                putExtra(TripRequestData.EXTRA_KEY, tripRequest)
            }
        }
    }
}
