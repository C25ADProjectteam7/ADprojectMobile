package iss.nus.edu.sg.viewbinding.caproject.ui.trips

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import iss.nus.edu.sg.viewbinding.caproject.R
import iss.nus.edu.sg.viewbinding.caproject.data.mock.CurrentTripStore
import iss.nus.edu.sg.viewbinding.caproject.data.mock.MockTravelData
import iss.nus.edu.sg.viewbinding.caproject.databinding.ActivityMockBookingConfirmationBinding
import iss.nus.edu.sg.viewbinding.caproject.model.TripRequestData
import iss.nus.edu.sg.viewbinding.caproject.ui.main.MainActivity
import java.text.NumberFormat
import java.util.Locale

class MockBookingConfirmationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMockBookingConfirmationBinding
    private lateinit var tripRequest: TripRequestData

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        binding = ActivityMockBookingConfirmationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindBooking()
        setupActions()
        setupBottomNavigation()
    }

    private fun bindBooking() {
        tripRequest = IntentCompat.getSerializableExtra(
            intent,
            TripRequestData.EXTRA_KEY,
            TripRequestData::class.java,
        ) ?: CurrentTripStore.currentTrip
        CurrentTripStore.confirmMockBooking(tripRequest)
        val itinerary = MockTravelData.itineraryFor(tripRequest)
        val booking = MockTravelData.bookingFor(tripRequest)

        binding.bookingSubtitle.text = getString(
            R.string.booking_subtitle_format,
            tripRequest.city,
            booking.dateRange,
        )
        binding.flightRoute.text = itinerary.flightRoute
        binding.flightDetails.text = booking.flightDetails
        binding.flightTerminal.text = booking.flightTerminal
        binding.flightNumber.text = itinerary.flightNumber
        binding.hotelName.text = itinerary.hotelName
        binding.hotelStay.text = itinerary.hotelStay
        binding.hotelRoom.text = booking.hotelRoom
        binding.hotelConfirmationCode.text = booking.hotelConfirmationCode
        binding.bookingReference.text = booking.reference
        binding.estimatedTotal.text = formatSgd(itinerary.estimatedTotal)

        val isWithinBudget = itinerary.estimatedTotal <= tripRequest.budget
        binding.budgetStatus.setText(
            if (isWithinBudget) R.string.within_budget else R.string.over_budget,
        )
        binding.budgetStatus.setTextColor(
            getColor(if (isWithinBudget) R.color.travel_green else R.color.travel_gold_dark),
        )
        binding.budgetStatus.setBackgroundResource(
            if (isWithinBudget) R.drawable.bg_status_green else R.drawable.bg_status_gold,
        )
    }

    private fun setupActions() {
        binding.backButton.setOnClickListener { finish() }
        binding.viewItineraryButton.setOnClickListener { finish() }
        binding.viewMyTripButton.setOnClickListener {
            startActivity(TripDetailActivity.createIntent(this, tripRequest))
        }
    }

    private fun setupBottomNavigation() {
        binding.bookingBottomNavigation.selectedItemId = R.id.navigation_trips
        binding.bookingBottomNavigation.setOnItemSelectedListener { item ->
            openMainTab(item.itemId)
            true
        }
        binding.bookingBottomNavigation.setOnItemReselectedListener {
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

    companion object {
        fun createIntent(
            context: Context,
            tripRequest: TripRequestData,
        ): Intent {
            return Intent(context, MockBookingConfirmationActivity::class.java).apply {
                putExtra(TripRequestData.EXTRA_KEY, tripRequest)
            }
        }
    }
}
