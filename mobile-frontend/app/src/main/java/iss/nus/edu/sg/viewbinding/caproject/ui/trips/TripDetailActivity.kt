package iss.nus.edu.sg.viewbinding.caproject.ui.trips

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import com.google.android.material.snackbar.Snackbar
import iss.nus.edu.sg.viewbinding.caproject.R
import iss.nus.edu.sg.viewbinding.caproject.data.mock.CurrentTripStore
import iss.nus.edu.sg.viewbinding.caproject.data.mock.MockTravelData
import iss.nus.edu.sg.viewbinding.caproject.databinding.ActivityTripDetailBinding
import iss.nus.edu.sg.viewbinding.caproject.databinding.ItemItineraryTimelineBinding
import iss.nus.edu.sg.viewbinding.caproject.model.DailyItinerary
import iss.nus.edu.sg.viewbinding.caproject.model.ItineraryItemState
import iss.nus.edu.sg.viewbinding.caproject.model.TripRequestData
import iss.nus.edu.sg.viewbinding.caproject.ui.expense.AddExpenseActivity
import iss.nus.edu.sg.viewbinding.caproject.ui.main.MainActivity
import java.time.format.DateTimeFormatter
import java.util.Locale

class TripDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTripDetailBinding
    private lateinit var tripRequest: TripRequestData
    private lateinit var days: List<DailyItinerary>
    private var selectedDayIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        binding = ActivityTripDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tripRequest = IntentCompat.getSerializableExtra(
            intent,
            TripRequestData.EXTRA_KEY,
            TripRequestData::class.java,
        ) ?: CurrentTripStore.currentTrip
        days = MockTravelData.dailyItineraryFor(tripRequest)

        bindTripHeader()
        bindSelectedDay()
        setupActions()
        setupBottomNavigation()
    }

    private fun bindTripHeader() {
        val booking = MockTravelData.bookingFor(tripRequest)
        binding.tripDetailTitle.text = getString(R.string.business_trip_format, tripRequest.city)
        binding.tripDetailSubtitle.text = getString(
            R.string.trip_detail_subtitle_format,
            if (CurrentTripStore.isMockBooked) getString(R.string.mock_booked) else getString(R.string.itinerary_ready),
            booking.dateRange,
        )
        binding.dayProgress.max = days.size
    }

    private fun bindSelectedDay() {
        val day = days[selectedDayIndex]
        binding.dayLabel.text = getString(
            R.string.trip_day_label_format,
            day.dayNumber,
            days.size,
        )
        binding.dayDate.text = day.date.format(DATE_FORMATTER)
        binding.dayRoute.text = day.route
        binding.dayProgress.progress = day.dayNumber
        binding.previousDayButton.isEnabled = selectedDayIndex > 0
        binding.previousDayButton.alpha = if (selectedDayIndex > 0) 1f else 0.35f
        binding.nextDayButton.isEnabled = selectedDayIndex < days.lastIndex
        binding.nextDayButton.alpha = if (selectedDayIndex < days.lastIndex) 1f else 0.35f

        binding.timelineContainer.removeAllViews()
        day.items.forEach { item ->
            val itemBinding = ItemItineraryTimelineBinding.inflate(
                layoutInflater,
                binding.timelineContainer,
                false,
            )
            itemBinding.itemTitle.text = getString(
                R.string.itinerary_item_title_format,
                item.time,
                item.title,
            )
            itemBinding.itemDetail.text = item.detail
            val color = when (item.state) {
                ItineraryItemState.CONFIRMED -> R.color.travel_green
                ItineraryItemState.UPCOMING -> R.color.travel_gold
                ItineraryItemState.PLANNED -> R.color.travel_border
            }
            itemBinding.timelineMarker.backgroundTintList = ColorStateList.valueOf(getColor(color))
            binding.timelineContainer.addView(itemBinding.root)
        }
    }

    private fun setupActions() {
        binding.backButton.setOnClickListener { finish() }
        binding.previousDayButton.setOnClickListener {
            if (selectedDayIndex > 0) {
                selectedDayIndex -= 1
                bindSelectedDay()
            }
        }
        binding.nextDayButton.setOnClickListener {
            if (selectedDayIndex < days.lastIndex) {
                selectedDayIndex += 1
                bindSelectedDay()
            }
        }
        binding.previewRecommendationsButton.setOnClickListener {
            Snackbar.make(
                binding.root,
                R.string.recommendations_mock_notice,
                Snackbar.LENGTH_LONG,
            ).show()
        }
        binding.requestModificationButton.setOnClickListener {
            Snackbar.make(
                binding.root,
                R.string.modification_mock_notice,
                Snackbar.LENGTH_LONG,
            ).show()
        }
        binding.addExpenseButton.setOnClickListener {
            startActivity(AddExpenseActivity.createIntent(this, tripRequest))
        }
    }

    private fun setupBottomNavigation() {
        binding.tripDetailBottomNavigation.selectedItemId = R.id.navigation_trips
        binding.tripDetailBottomNavigation.setOnItemSelectedListener { item ->
            openMainTab(item.itemId)
            true
        }
        binding.tripDetailBottomNavigation.setOnItemReselectedListener {
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

    companion object {
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)

        fun createIntent(context: Context, tripRequest: TripRequestData): Intent {
            return Intent(context, TripDetailActivity::class.java).apply {
                putExtra(TripRequestData.EXTRA_KEY, tripRequest)
            }
        }
    }
}
