package iss.nus.edu.sg.viewbinding.caproject.ui.trips

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import iss.nus.edu.sg.viewbinding.caproject.R
import iss.nus.edu.sg.viewbinding.caproject.data.local.CurrentTripStore
import iss.nus.edu.sg.viewbinding.caproject.data.repository.BookingRepository
import iss.nus.edu.sg.viewbinding.caproject.databinding.ActivityMockBookingConfirmationBinding
import iss.nus.edu.sg.viewbinding.caproject.model.BookingRecord
import iss.nus.edu.sg.viewbinding.caproject.model.TripRequestData
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult
import iss.nus.edu.sg.viewbinding.caproject.ui.auth.AuthenticatedActivity
import iss.nus.edu.sg.viewbinding.caproject.ui.main.MainActivity
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

class MockBookingConfirmationActivity : AuthenticatedActivity() {

    private lateinit var binding: ActivityMockBookingConfirmationBinding
    private lateinit var bookingRepository: BookingRepository
    private lateinit var tripRequest: TripRequestData
    private var bookingIds: List<Long> = emptyList()
    private var bookingRecords: List<BookingRecord> = emptyList()
    private var retryAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        binding = ActivityMockBookingConfirmationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        bookingRepository = BookingRepository.create(this)
        val suppliedTrip = IntentCompat.getSerializableExtra(
            intent,
            TripRequestData.EXTRA_KEY,
            TripRequestData::class.java,
        ) ?: CurrentTripStore.currentTrip
        if (suppliedTrip == null) {
            finish()
            return
        }
        tripRequest = suppliedTrip
        bookingIds = intent.getLongArrayExtra(EXTRA_BOOKING_IDS)?.toList().orEmpty()

        setupActions()
        setupBottomNavigation()
        loadBookings()
    }

    private fun loadBookings() {
        val tripId = tripRequest.remoteId
        if (tripId == null) {
            showState(getString(R.string.booking_not_found), retry = null)
            return
        }
        showLoading()
        lifecycleScope.launch {
            val result = if (bookingIds.isEmpty()) {
                bookingRepository.getTripBookings(tripId)
            } else {
                bookingRepository.getBookingDetails(bookingIds)
            }
            when (result) {
                is ApiResult.Failure -> showState(
                    message = bookingMessageFor(result),
                    retry = if (result.isBookingRetryable()) ::loadBookings else null,
                )

                is ApiResult.Success -> {
                    val records = result.value.filter { it.tripId == tripId }
                    if (records.isEmpty()) {
                        showState(getString(R.string.booking_no_records), retry = ::loadBookings)
                    } else {
                        bookingRecords = records
                        bookingIds = records.map(BookingRecord::id)
                        bindBookings(records)
                    }
                }
            }
        }
    }

    private fun bindBookings(records: List<BookingRecord>) {
        retryAction = null
        binding.bookingLoading.isVisible = false
        binding.bookingStateContainer.isVisible = false
        setContentVisible(true)

        binding.bookingSubtitle.text = getString(
            R.string.booking_subtitle_format,
            tripRequest.city,
            formatDateRange(),
        )
        val flight = records.lastOrNull {
            it.type.equals(BookingRecord.TYPE_FLIGHT, ignoreCase = true)
        }
        val hotel = records.lastOrNull {
            it.type.equals(BookingRecord.TYPE_HOTEL, ignoreCase = true)
        }
        bindFlight(flight)
        bindHotel(hotel)
        bindSummary(records)
        binding.cancelBookingButton.isVisible = records.any { !it.isCancelled }
    }

    private fun bindFlight(booking: BookingRecord?) {
        binding.flightBookingCard.isVisible = booking != null
        if (booking == null) return
        binding.flightRoute.text = getString(
            R.string.trip_route_format,
            "Singapore",
            tripRequest.city,
        )
        binding.flightDetails.text = getString(
            R.string.booking_record_details_format,
            booking.id,
            booking.status.toDisplayLabel(),
        )
        binding.flightTerminal.setText(R.string.booking_provider_simulation)
        binding.flightNumber.text = booking.bookingRef ?: getString(R.string.booking_reference_missing)
        bindStatus(binding.flightStatus, booking.status)
    }

    private fun bindHotel(booking: BookingRecord?) {
        binding.hotelBookingCard.isVisible = booking != null
        if (booking == null) return
        binding.hotelName.text = getString(R.string.booking_hotel_record_title, tripRequest.city)
        binding.hotelStay.text = formatDateRange()
        binding.hotelRoom.text = getString(
            R.string.booking_record_details_format,
            booking.id,
            booking.status.toDisplayLabel(),
        )
        binding.hotelConfirmationCode.text = booking.bookingRef
            ?: getString(R.string.booking_reference_missing)
        bindStatus(binding.hotelStatus, booking.status)
    }

    private fun bindSummary(records: List<BookingRecord>) {
        binding.bookingReference.text = records.joinToString(separator = " · ") { booking ->
            booking.bookingRef ?: "#${booking.id}"
        }
        val pricedBookings = records.filter { it.price != null }
        val currencies = pricedBookings
            .map { it.currency.orEmpty().ifBlank { "SGD" }.uppercase(Locale.ENGLISH) }
            .distinct()
        val totalCurrency = currencies.singleOrNull()
        val total = pricedBookings.mapNotNull { it.price }
            .fold(BigDecimal.ZERO, BigDecimal::add)

        binding.estimatedTotal.text = when {
            pricedBookings.isEmpty() -> getString(R.string.booking_total_unavailable)
            totalCurrency == null -> getString(R.string.mixed_currency_total)
            else -> formatMoney(total, totalCurrency)
        }
        val withinBudget = totalCurrency == "SGD" && total.toDouble() <= tripRequest.budget
        val budgetString = when {
            totalCurrency != "SGD" -> R.string.booking_budget_review
            withinBudget -> R.string.within_budget
            else -> R.string.over_budget
        }
        binding.budgetStatus.setText(budgetString)
        val positiveStatus = totalCurrency == "SGD" && withinBudget
        binding.budgetStatus.setTextColor(
            getColor(if (positiveStatus) R.color.travel_green else R.color.travel_gold_dark),
        )
        binding.budgetStatus.setBackgroundResource(
            if (positiveStatus) R.drawable.bg_status_green else R.drawable.bg_status_gold,
        )
    }

    private fun bindStatus(view: TextView, status: String) {
        view.text = getString(R.string.booking_status_format, status.toDisplayLabel())
        val confirmed = status.equals("CONFIRMED", ignoreCase = true)
        view.setTextColor(
            getColor(if (confirmed) R.color.travel_green else R.color.travel_gold_dark),
        )
        view.setBackgroundResource(
            if (confirmed) R.drawable.bg_status_green else R.drawable.bg_status_gold,
        )
    }

    private fun requestCancellation() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cancel_mock_booking)
            .setMessage(R.string.cancel_booking_confirmation)
            .setNegativeButton(R.string.keep_booking, null)
            .setPositiveButton(R.string.cancel_mock_booking) { _, _ -> cancelActiveBookings() }
            .show()
    }

    private fun cancelActiveBookings() {
        showLoading()
        lifecycleScope.launch {
            when (val result = bookingRepository.cancelBookings(bookingRecords)) {
                is ApiResult.Failure -> showState(
                    message = bookingMessageFor(result),
                    retry = if (result.isBookingRetryable()) ::cancelActiveBookings else null,
                )

                is ApiResult.Success -> {
                    Toast.makeText(
                        this@MockBookingConfirmationActivity,
                        R.string.booking_cancelled,
                        Toast.LENGTH_SHORT,
                    ).show()
                    loadBookings()
                }
            }
        }
    }

    private fun showLoading() {
        retryAction = null
        binding.bookingLoading.isVisible = true
        binding.bookingStateContainer.isVisible = false
        setContentVisible(false)
    }

    private fun showState(message: String, retry: (() -> Unit)?) {
        retryAction = retry
        binding.bookingLoading.isVisible = false
        setContentVisible(false)
        binding.bookingStateContainer.isVisible = true
        binding.bookingStateMessage.text = message
        binding.bookingRetryButton.isVisible = retry != null
    }

    private fun setContentVisible(visible: Boolean) {
        binding.bookingSimulationNotice.isVisible = visible
        binding.flightBookingCard.isVisible = visible
        binding.hotelBookingCard.isVisible = visible
        binding.bookingSummaryCard.isVisible = visible
        binding.bookingActions.isVisible = visible
        binding.cancelBookingButton.isVisible = visible
    }

    private fun setupActions() {
        binding.backButton.setOnClickListener { finish() }
        binding.bookingRetryButton.setOnClickListener { retryAction?.invoke() }
        binding.viewItineraryButton.setOnClickListener { finish() }
        binding.viewMyTripButton.setOnClickListener {
            startActivity(TripDetailActivity.createIntent(this, tripRequest))
        }
        binding.cancelBookingButton.setOnClickListener { requestCancellation() }
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

    private fun formatDateRange(): String {
        val startDate = tripRequest.startDate
        val endDate = tripRequest.endDate
        val sameMonth = startDate.month == endDate.month && startDate.year == endDate.year
        return if (sameMonth) {
            val monthYear = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)
            "${startDate.dayOfMonth}–${endDate.dayOfMonth} ${endDate.format(monthYear)}"
        } else {
            val fullDate = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
            "${startDate.format(fullDate)}–${endDate.format(fullDate)}"
        }
    }

    private fun formatMoney(value: BigDecimal, currency: String): String {
        val formatter = NumberFormat.getNumberInstance(Locale.ENGLISH).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 2
        }
        return "$currency ${formatter.format(value)}"
    }

    private fun String.toDisplayLabel(): String {
        return replace('_', ' ')
            .lowercase(Locale.ENGLISH)
            .replaceFirstChar { it.titlecase(Locale.ENGLISH) }
    }

    companion object {
        private const val EXTRA_BOOKING_IDS = "booking_ids"

        fun createIntent(
            context: Context,
            tripRequest: TripRequestData,
            bookingIds: List<Long>,
        ): Intent {
            return Intent(context, MockBookingConfirmationActivity::class.java).apply {
                putExtra(TripRequestData.EXTRA_KEY, tripRequest)
                putExtra(EXTRA_BOOKING_IDS, bookingIds.toLongArray())
            }
        }
    }
}
