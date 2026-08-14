package iss.nus.edu.sg.viewbinding.caproject.ui.trips

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import iss.nus.edu.sg.viewbinding.caproject.R
import iss.nus.edu.sg.viewbinding.caproject.data.local.CurrentTripStore
import iss.nus.edu.sg.viewbinding.caproject.data.repository.AgentPollResult
import iss.nus.edu.sg.viewbinding.caproject.data.repository.AgentTaskProgress
import iss.nus.edu.sg.viewbinding.caproject.data.repository.AgentTripPlanner
import iss.nus.edu.sg.viewbinding.caproject.data.repository.BookingRepository
import iss.nus.edu.sg.viewbinding.caproject.data.repository.MlRepository
import iss.nus.edu.sg.viewbinding.caproject.data.repository.TripRepository
import iss.nus.edu.sg.viewbinding.caproject.databinding.ActivityItineraryReviewBinding
import iss.nus.edu.sg.viewbinding.caproject.model.ItineraryItem
import iss.nus.edu.sg.viewbinding.caproject.model.HotelPricePrediction
import iss.nus.edu.sg.viewbinding.caproject.model.TripDetailData
import iss.nus.edu.sg.viewbinding.caproject.model.TripRequestData
import iss.nus.edu.sg.viewbinding.caproject.network.ApiFailureKind
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult
import iss.nus.edu.sg.viewbinding.caproject.ui.auth.AuthenticatedActivity
import iss.nus.edu.sg.viewbinding.caproject.ui.main.MainActivity
import iss.nus.edu.sg.viewbinding.caproject.validation.BudgetCalculator
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

class ItineraryReviewActivity : AuthenticatedActivity() {

    private lateinit var binding: ActivityItineraryReviewBinding
    private lateinit var tripRepository: TripRepository
    private lateinit var agentTripPlanner: AgentTripPlanner
    private lateinit var bookingRepository: BookingRepository
    private lateinit var mlRepository: MlRepository
    private lateinit var tripRequest: TripRequestData
    private var currentTripDetail: TripDetailData? = null
    private var retryAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        binding = ActivityItineraryReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        tripRepository = TripRepository.create(this)
        agentTripPlanner = AgentTripPlanner.create(this)
        bookingRepository = BookingRepository.create(this)
        mlRepository = MlRepository.create(this)
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

        setupActions()
        setupBottomNavigation()
        loadTripDetail()
    }

    private fun loadTripDetail(expectGeneratedItinerary: Boolean = false) {
        val tripId = tripRequest.remoteId
        if (tripId == null) {
            showState(getString(R.string.trip_not_found), retry = null)
            return
        }
        showLoading()
        lifecycleScope.launch {
            when (val result = tripRepository.getTripDetail(tripId)) {
                is ApiResult.Failure -> showState(
                    message = tripMessageFor(result),
                    retry = if (result.isTripRetryable()) {
                        { loadTripDetail(expectGeneratedItinerary) }
                    } else {
                        null
                    },
                )

                is ApiResult.Success -> {
                    tripRequest = result.value.trip
                    CurrentTripStore.saveRequest(tripRequest)
                    when {
                        result.value.days.isNotEmpty() -> showItinerary(result.value)
                        expectGeneratedItinerary -> showState(
                            message = getString(R.string.agent_itinerary_invalid_response),
                            retry = ::generateItinerary,
                        )
                        else -> generateItinerary()
                    }
                }
            }
        }
    }

    private fun generateItinerary() {
        showGenerating()
        lifecycleScope.launch {
            when (
                val result = agentTripPlanner.generateOrResume(
                    trip = tripRequest,
                    onProgress = ::showAgentProgress,
                )
            ) {
                AgentPollResult.ItineraryReady -> loadTripDetail(expectGeneratedItinerary = true)
                is AgentPollResult.NeedsMoreInfo -> showNeedsMoreInformation(result)
                is AgentPollResult.TaskFailed -> showState(
                    message = result.message?.let {
                        getString(R.string.agent_task_failed_format, it)
                    } ?: getString(R.string.agent_itinerary_failed),
                    retry = ::generateItinerary,
                )
                AgentPollResult.TimedOut -> showState(
                    message = getString(R.string.agent_itinerary_timeout),
                    retry = ::generateItinerary,
                )
                is AgentPollResult.RequestFailure -> showState(
                    message = tripMessageFor(result.failure),
                    retry = if (
                        result.failure.isTripRetryable() ||
                        result.failure.kind == ApiFailureKind.NOT_FOUND
                    ) {
                        ::generateItinerary
                    } else {
                        null
                    },
                )
                AgentPollResult.InvalidResponse -> showState(
                    message = getString(R.string.agent_itinerary_invalid_response),
                    retry = ::generateItinerary,
                )
            }
        }
    }

    private fun showNeedsMoreInformation(result: AgentPollResult.NeedsMoreInfo) {
        val question = result.clarifyingQuestion
            ?: result.missingFields.joinToString().takeIf(String::isNotBlank)
            ?: getString(R.string.trip_validation_failed)
        showState(
            message = getString(R.string.agent_missing_information, question),
            retry = { finish() },
            retryLabel = getString(R.string.return_to_trip_form),
        )
    }

    private fun showLoading() {
        retryAction = null
        setReviewVisible(false)
        binding.itineraryStateContainer.isVisible = false
        binding.agentTaskStatus.isVisible = false
        binding.itineraryLoading.isVisible = true
    }

    private fun showGenerating() {
        showLoading()
        binding.agentTaskStatus.isVisible = true
        binding.agentTaskStatus.setText(R.string.agent_generating_itinerary)
    }

    private fun showAgentProgress(progress: AgentTaskProgress) {
        binding.agentTaskStatus.isVisible = true
        binding.agentTaskStatus.text = getString(
            R.string.agent_task_progress_format,
            progress.taskId,
            progress.status,
        )
    }

    private fun showState(
        message: String,
        retry: (() -> Unit)?,
        retryLabel: String = getString(R.string.retry),
    ) {
        retryAction = retry
        binding.itineraryLoading.isVisible = false
        binding.agentTaskStatus.isVisible = false
        setReviewVisible(false)
        binding.itineraryStateContainer.isVisible = true
        binding.itineraryStateMessage.text = message
        binding.itineraryRetryButton.isVisible = retry != null
        binding.itineraryRetryButton.text = retryLabel
    }

    private fun showItinerary(detail: TripDetailData) {
        currentTripDetail = detail
        binding.itineraryLoading.isVisible = false
        binding.agentTaskStatus.isVisible = false
        binding.itineraryStateContainer.isVisible = false
        setReviewVisible(true)
        setBookingActionsEnabled(true)

        val allItems = detail.days.flatMap { it.items }
        val flight = allItems.firstOrNull { it.type.equals(TYPE_FLIGHT, ignoreCase = true) }
        val hotel = allItems.firstOrNull { it.type.equals(TYPE_HOTEL, ignoreCase = true) }
        val highlight = allItems.firstOrNull {
            !it.type.equals(TYPE_FLIGHT, ignoreCase = true) &&
                !it.type.equals(TYPE_HOTEL, ignoreCase = true)
        }

        bindHeader()
        bindFlight(flight)
        bindHotel(hotel)
        bindHighlight(highlight)
        bindBudget(allItems)
    }

    private fun bindHeader() {
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
            .takeIf(String::isNotBlank)
            ?.let { getString(R.string.itinerary_notes_format, it) }
            .orEmpty()
        binding.agentMessage.text = getString(
            R.string.itinerary_agent_message_format,
            tripRequest.city,
            formatDateRange(),
            preferences,
            notes,
        )
    }

    private fun bindFlight(item: ItineraryItem?) {
        binding.flightCard.isVisible = item != null
        if (item == null) return
        binding.flightRoute.text = getString(R.string.trip_route_format, "Singapore", tripRequest.city)
        binding.flightSummary.text = item.title.ifBlank { getString(R.string.no_flight_in_itinerary) }
        binding.flightSchedule.text = formatSchedule(item)
        binding.flightNumber.text = item.bookingRef ?: item.type.toDisplayLabel()
        binding.flightPrice.text = item.price?.let { formatMoney(it, item.currency) }
            ?: getString(R.string.price_pending)
    }

    private fun bindHotel(item: ItineraryItem?) {
        binding.hotelCard.isVisible = item != null
        if (item == null) return
        binding.hotelName.text = item.title.ifBlank { getString(R.string.no_hotel_in_itinerary) }
        binding.hotelSummary.text = item.location ?: item.detail
        binding.hotelStay.text = formatSchedule(item)
        binding.hotelRate.text = item.bookingRef ?: item.type.toDisplayLabel()
        binding.hotelPrice.text = item.price?.let { formatMoney(it, item.currency) }
            ?: getString(R.string.price_pending)
        loadHotelPrediction(item)
    }

    private fun loadHotelPrediction(hotel: ItineraryItem) {
        binding.hotelPrediction.setText(R.string.ml_prediction_loading)
        binding.hotelPrediction.isClickable = false
        lifecycleScope.launch {
            when (
                val result = mlRepository.predictHotelPrice(
                    city = tripRequest.city,
                    checkInDate = tripRequest.startDate,
                    checkOutDate = tripRequest.endDate,
                    hotelStarRating = hotelStarRating(hotel),
                    roomType = hotelRoomType(hotel),
                    numberOfGuests = 1,
                    currency = "USD",
                )
            ) {
                is ApiResult.Success -> bindHotelPrediction(result.value)
                is ApiResult.Failure -> {
                    val message = mlMessageFor(result)
                    binding.hotelPrediction.text = if (result.isMlRetryable()) {
                        getString(R.string.ml_prediction_retry_format, message)
                    } else {
                        message
                    }
                    binding.hotelPrediction.isClickable = result.isMlRetryable()
                    binding.hotelPrediction.setOnClickListener {
                        if (result.isMlRetryable()) loadHotelPrediction(hotel)
                    }
                }
            }
        }
    }

    private fun bindHotelPrediction(prediction: HotelPricePrediction) {
        val metadata = listOf(prediction.modelStatus, prediction.modelVersion)
            .filter(String::isNotBlank)
            .joinToString(" ")
            .takeIf(String::isNotBlank)
            ?.let { getString(R.string.ml_prediction_metadata_format, "Model", it) }
            .orEmpty()
        binding.hotelPrediction.text = getString(
            if (prediction.isMock) {
                R.string.ml_prediction_mock_format
            } else {
                R.string.ml_prediction_live_format
            },
            formatMoney(prediction.predictedPricePerNight, prediction.currency),
            formatMoney(prediction.predictedTotalPrice, prediction.currency),
            prediction.numberOfNights,
            metadata,
        )
        binding.hotelPrediction.isClickable = false
        binding.hotelPrediction.setOnClickListener(null)
    }

    private fun hotelStarRating(hotel: ItineraryItem): Int {
        val text = "${hotel.title} ${hotel.detail}"
        return Regex("([1-5])(?:\\.\\d)?\\s*(?:star|★)", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 4
    }

    private fun hotelRoomType(hotel: ItineraryItem): String {
        val text = "${hotel.title} ${hotel.detail}".lowercase(Locale.ENGLISH)
        return when {
            "suite" in text -> "suite"
            "twin" in text -> "twin"
            "single" in text -> "single"
            else -> "double"
        }
    }

    private fun bindHighlight(item: ItineraryItem?) {
        binding.highlightCard.isVisible = item != null
        if (item == null) return
        binding.transferTitle.text = item.title.ifBlank { getString(R.string.no_other_itinerary_item) }
        binding.transferDescription.text = item.detail
        binding.transferDuration.text = formatSchedule(item)
    }

    private fun bindBudget(items: List<ItineraryItem>) {
        val pricedItems = items.mapNotNull { it.price }
        if (pricedItems.isEmpty()) {
            binding.budgetTotal.text = getString(
                R.string.price_estimates_pending,
                formatSgd(BigDecimal.valueOf(tripRequest.budget)),
            )
            binding.budgetProgress.progress = 0
            return
        }
        val estimatedTotal = pricedItems.fold(BigDecimal.ZERO, BigDecimal::add)
        val currencies = items
            .filter { it.price != null }
            .map { it.currency.orEmpty().ifBlank { "SGD" }.uppercase(Locale.ENGLISH) }
            .distinct()
        val totalCurrency = currencies.singleOrNull()
        binding.budgetTotal.text = getString(
            R.string.budget_total_format,
            totalCurrency?.let { formatMoney(estimatedTotal, it) }
                ?: getString(R.string.mixed_currency_total),
            formatSgd(BigDecimal.valueOf(tripRequest.budget)),
        )
        binding.budgetProgress.progress = if (totalCurrency == "SGD") {
            BudgetCalculator.progressPercent(estimatedTotal.toDouble(), tripRequest.budget)
        } else {
            0
        }
    }

    private fun setReviewVisible(visible: Boolean) {
        binding.agentIntroRow.isVisible = visible
        binding.itineraryDataNotice.isVisible = visible
        binding.flightCard.isVisible = visible
        binding.hotelCard.isVisible = visible
        binding.highlightCard.isVisible = visible
        binding.budgetCard.isVisible = visible
        binding.itineraryActions.isVisible = visible
    }

    private fun setupActions() {
        binding.backButton.setOnClickListener { finish() }
        binding.itineraryRetryButton.setOnClickListener { retryAction?.invoke() }
        binding.requestChangesButton.setOnClickListener {
            tripRequest.remoteId?.let { tripId ->
                startActivity(TripDetailActivity.createAgentRequestIntent(this, tripId))
            }
        }
        binding.confirmMockBookingButton.setOnClickListener {
            confirmMockBooking()
        }
    }

    private fun confirmMockBooking() {
        val detail = currentTripDetail
        val tripId = tripRequest.remoteId
        if (detail == null || tripId == null) {
            showState(getString(R.string.booking_not_found), retry = { loadTripDetail() })
            return
        }
        val items = detail.days.flatMap { it.items }
        val hasBookableItem = items.any {
            it.type.equals(TYPE_FLIGHT, ignoreCase = true) ||
                it.type.equals(TYPE_HOTEL, ignoreCase = true)
        }
        if (!hasBookableItem) {
            showState(
                message = getString(R.string.booking_no_bookable_items),
                retry = { loadTripDetail() },
            )
            return
        }

        showBookingSaving()
        lifecycleScope.launch {
            when (val result = bookingRepository.confirmTripBookings(tripRequest, items)) {
                is ApiResult.Failure -> showState(
                    message = bookingMessageFor(result),
                    retry = if (result.isBookingRetryable()) ::confirmMockBooking else null,
                )

                is ApiResult.Success -> startActivity(
                    MockBookingConfirmationActivity.createIntent(
                        context = this@ItineraryReviewActivity,
                        tripRequest = tripRequest,
                        bookingIds = result.value.map { it.id },
                    ),
                )
            }
        }
    }

    private fun showBookingSaving() {
        retryAction = null
        binding.itineraryLoading.isVisible = false
        binding.itineraryStateContainer.isVisible = false
        binding.agentTaskStatus.isVisible = true
        binding.agentTaskStatus.setText(R.string.booking_saving_records)
        setReviewVisible(true)
        setBookingActionsEnabled(false)
    }

    private fun setBookingActionsEnabled(enabled: Boolean) {
        binding.requestChangesButton.isEnabled = enabled
        binding.confirmMockBookingButton.isEnabled = enabled
        binding.confirmMockBookingButton.text = getString(
            if (enabled) R.string.confirm_mock_booking else R.string.saving_mock_booking,
        )
    }

    private fun setupBottomNavigation() {
        binding.itineraryBottomNavigation.selectedItemId = R.id.navigation_trips
        binding.itineraryBottomNavigation.setOnItemSelectedListener { item ->
            openMainTab(item.itemId)
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

    private fun formatSchedule(item: ItineraryItem): String {
        return item.endTime?.let { "${item.time}–$it" } ?: item.time
    }

    private fun String.toDisplayLabel(): String {
        return replace('_', ' ')
            .lowercase(Locale.ENGLISH)
            .replaceFirstChar { it.titlecase(Locale.ENGLISH) }
    }

    private fun formatSgd(value: BigDecimal): String {
        return formatMoney(value, "SGD").replaceFirst("SGD ", "S$")
    }

    private fun formatMoney(value: BigDecimal, currency: String?): String {
        val formatter = NumberFormat.getNumberInstance(Locale.ENGLISH).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 2
        }
        val currencyCode = currency.orEmpty().ifBlank { "SGD" }.uppercase(Locale.ENGLISH)
        return "$currencyCode ${formatter.format(value)}"
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
        private const val TYPE_FLIGHT = "FLIGHT"
        private const val TYPE_HOTEL = "HOTEL"

        fun createIntent(context: Context, tripRequest: TripRequestData): Intent {
            return Intent(context, ItineraryReviewActivity::class.java).apply {
                putExtra(TripRequestData.EXTRA_KEY, tripRequest)
            }
        }
    }
}
