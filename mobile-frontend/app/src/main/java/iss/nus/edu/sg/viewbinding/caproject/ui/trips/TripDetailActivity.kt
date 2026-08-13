package iss.nus.edu.sg.viewbinding.caproject.ui.trips

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import iss.nus.edu.sg.viewbinding.caproject.R
import iss.nus.edu.sg.viewbinding.caproject.data.local.CurrentTripStore
import iss.nus.edu.sg.viewbinding.caproject.data.repository.AgentPollResult
import iss.nus.edu.sg.viewbinding.caproject.data.repository.AgentTaskProgress
import iss.nus.edu.sg.viewbinding.caproject.data.repository.AgentTripPlanner
import iss.nus.edu.sg.viewbinding.caproject.data.repository.TripRepository
import iss.nus.edu.sg.viewbinding.caproject.databinding.ActivityTripDetailBinding
import iss.nus.edu.sg.viewbinding.caproject.databinding.DialogAgentChatBinding
import iss.nus.edu.sg.viewbinding.caproject.databinding.ItemItineraryTimelineBinding
import iss.nus.edu.sg.viewbinding.caproject.model.DailyItinerary
import iss.nus.edu.sg.viewbinding.caproject.model.ItineraryItemState
import iss.nus.edu.sg.viewbinding.caproject.model.TripRequestData
import iss.nus.edu.sg.viewbinding.caproject.network.ApiFailureKind
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult
import iss.nus.edu.sg.viewbinding.caproject.ui.auth.AuthenticatedActivity
import iss.nus.edu.sg.viewbinding.caproject.ui.expense.AddExpenseActivity
import iss.nus.edu.sg.viewbinding.caproject.ui.main.MainActivity
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class TripDetailActivity : AuthenticatedActivity() {

    private lateinit var binding: ActivityTripDetailBinding
    private lateinit var tripRepository: TripRepository
    private lateinit var agentTripPlanner: AgentTripPlanner
    private var tripId: Long = INVALID_TRIP_ID
    private var trip: TripRequestData? = null
    private var days: List<DailyItinerary> = emptyList()
    private var selectedDayIndex = 0
    private var agentJob: Job? = null
    private var agentTaskRetryAction: (() -> Unit)? = null
    private var pendingAgentRequest: String? = null
    private var openAgentDialogRequested = false

    private val editTripLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Snackbar.make(binding.root, R.string.trip_updated, Snackbar.LENGTH_SHORT).show()
            loadTrip()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        binding = ActivityTripDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        tripRepository = TripRepository.create(this)
        agentTripPlanner = AgentTripPlanner.create(this)
        pendingAgentRequest = savedInstanceState?.getString(STATE_PENDING_AGENT_REQUEST)
        openAgentDialogRequested = intent.getBooleanExtra(EXTRA_OPEN_AGENT_DIALOG, false)

        tripId = intent.getLongExtra(EXTRA_TRIP_ID, INVALID_TRIP_ID).takeIf {
            it != INVALID_TRIP_ID
        } ?: IntentCompat.getSerializableExtra(
            intent,
            TripRequestData.EXTRA_KEY,
            TripRequestData::class.java,
        )?.remoteId ?: INVALID_TRIP_ID

        setupActions()
        setupBottomNavigation()
        if (tripId == INVALID_TRIP_ID) {
            showState(getString(R.string.trip_not_found), canRetry = false)
        } else {
            loadTrip()
        }
    }

    private fun loadTrip() {
        showLoading()
        lifecycleScope.launch {
            when (val result = tripRepository.getTripDetail(tripId)) {
                is ApiResult.Success -> showTrip(result.value.trip, result.value.days)
                is ApiResult.Failure -> showState(
                    message = tripMessageFor(result),
                    canRetry = result.isTripRetryable(),
                )
            }
        }
    }

    private fun showLoading() {
        binding.tripDetailLoading.isVisible = true
        binding.tripDetailStateContainer.isVisible = false
        setContentVisible(false)
    }

    private fun showTrip(loadedTrip: TripRequestData, loadedDays: List<DailyItinerary>) {
        trip = loadedTrip
        days = loadedDays
        selectedDayIndex = 0
        CurrentTripStore.saveRequest(loadedTrip)

        binding.tripDetailLoading.isVisible = false
        binding.tripDetailStateContainer.isVisible = false
        setContentVisible(true)
        bindTripHeader(loadedTrip)

        val hasItinerary = days.isNotEmpty()
        binding.daySummaryCard.isVisible = hasItinerary
        binding.emptyItineraryMessage.isVisible = !hasItinerary
        binding.timelineContainer.isVisible = hasItinerary
        if (hasItinerary) bindSelectedDay()

        val isCancelled = loadedTrip.remoteStatus.equals("CANCELLED", ignoreCase = true)
        binding.editTripButton.isEnabled = !isCancelled
        binding.cancelTripButton.isEnabled = !isCancelled
        binding.requestModificationButton.isEnabled = !isCancelled
        binding.addExpenseButton.isEnabled = !isCancelled
        resumeAgentTaskOrOpenDialog(loadedTrip)
    }

    private fun bindTripHeader(loadedTrip: TripRequestData) {
        binding.tripDetailTitle.text = loadedTrip.displayTitle
        binding.tripDetailSubtitle.text = getString(
            R.string.trip_detail_subtitle_format,
            TripUiFormatter.status(loadedTrip),
            TripUiFormatter.dates(loadedTrip),
        )
        binding.dayProgress.max = days.size.coerceAtLeast(1)
    }

    private fun bindSelectedDay() {
        val day = days[selectedDayIndex]
        binding.dayLabel.text = getString(R.string.trip_day_label_format, day.dayNumber, days.size)
        binding.dayDate.text = day.date.format(DATE_FORMATTER)
        binding.dayRoute.text = day.route
        binding.dayProgress.progress = selectedDayIndex + 1
        binding.previousDayButton.isEnabled = selectedDayIndex > 0
        binding.previousDayButton.alpha = if (selectedDayIndex > 0) 1f else 0.35f
        binding.nextDayButton.isEnabled = selectedDayIndex < days.lastIndex
        binding.nextDayButton.alpha = if (selectedDayIndex < days.lastIndex) 1f else 0.35f

        binding.timelineContainer.removeAllViews()
        if (day.items.isEmpty()) {
            binding.emptyItineraryMessage.isVisible = true
            binding.timelineContainer.isVisible = false
            return
        }
        binding.emptyItineraryMessage.isVisible = false
        binding.timelineContainer.isVisible = true
        day.items.forEach { item ->
            val itemBinding = ItemItineraryTimelineBinding.inflate(
                layoutInflater,
                binding.timelineContainer,
                false,
            )
            itemBinding.itemTitle.text = getString(R.string.itinerary_item_title_format, item.time, item.title)
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

    private fun showState(message: String, canRetry: Boolean) {
        binding.tripDetailLoading.isVisible = false
        setContentVisible(false)
        binding.tripDetailStateContainer.isVisible = true
        binding.tripDetailStateMessage.text = message
        binding.tripDetailRetryButton.isVisible = canRetry
    }

    private fun setContentVisible(isVisible: Boolean) {
        binding.daySummaryCard.isVisible = isVisible
        binding.itineraryCard.isVisible = isVisible
        binding.recommendationsCard.isVisible = isVisible
        binding.tripPrimaryActions.isVisible = isVisible
        binding.tripManagementActions.isVisible = isVisible
        if (!isVisible) binding.agentTaskContainer.isVisible = false
    }

    private fun setupActions() {
        binding.backButton.setOnClickListener { finish() }
        binding.tripDetailRetryButton.setOnClickListener { loadTrip() }
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
            Snackbar.make(binding.root, R.string.recommendations_mock_notice, Snackbar.LENGTH_LONG).show()
        }
        binding.requestModificationButton.setOnClickListener { showAgentChatDialog() }
        binding.agentTaskRetryButton.setOnClickListener { agentTaskRetryAction?.invoke() }
        binding.addExpenseButton.setOnClickListener {
            trip?.let { startActivity(AddExpenseActivity.createIntent(this, it)) }
        }
        binding.editTripButton.setOnClickListener {
            trip?.let { editTripLauncher.launch(TripRequestActivity.createEditIntent(this, it)) }
        }
        binding.cancelTripButton.setOnClickListener { confirmCancelTrip() }
    }

    private fun confirmCancelTrip() {
        val currentTrip = trip ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cancel_trip)
            .setMessage(getString(R.string.cancel_trip_confirmation, currentTrip.displayTitle))
            .setNegativeButton(R.string.keep_trip, null)
            .setPositiveButton(R.string.cancel_trip) { _, _ -> cancelTrip() }
            .show()
    }

    private fun cancelTrip() {
        setActionsEnabled(false)
        lifecycleScope.launch {
            when (val result = tripRepository.cancelTrip(tripId)) {
                is ApiResult.Success -> {
                    Snackbar.make(binding.root, R.string.trip_cancelled, Snackbar.LENGTH_SHORT)
                        .addCallback(
                            object : Snackbar.Callback() {
                                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                                    finish()
                                }
                            },
                        )
                        .show()
                }

                is ApiResult.Failure -> {
                    setActionsEnabled(true)
                    val snackbar = Snackbar.make(binding.root, tripMessageFor(result), Snackbar.LENGTH_LONG)
                    if (result.isTripRetryable()) snackbar.setAction(R.string.retry) { cancelTrip() }
                    snackbar.show()
                }
            }
        }
    }

    private fun showAgentChatDialog() {
        if (agentJob?.isActive == true) return
        val dialogBinding = DialogAgentChatBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.request_modification)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.send_request, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val message = dialogBinding.agentMessageInput.text?.toString().orEmpty().trim()
                if (message.isBlank()) {
                    dialogBinding.agentMessageInputLayout.error = getString(R.string.agent_message_required)
                } else {
                    dialog.dismiss()
                    startAgentChat(message)
                }
            }
        }
        dialog.show()
    }

    private fun startAgentChat(message: String) {
        val currentTrip = trip ?: return
        pendingAgentRequest = message
        runAgentWork(currentTrip, requestedChange = message)
    }

    private fun resumeAgentTaskOrOpenDialog(currentTrip: TripRequestData) {
        if (agentJob?.isActive == true) return
        agentJob = lifecycleScope.launch {
            val result = agentTripPlanner.resumeIfPresent(
                trip = currentTrip,
                onProgress = ::showAgentProgress,
            )
            if (result == null) {
                agentJob = null
                hideAgentTaskStatus()
                if (openAgentDialogRequested) {
                    openAgentDialogRequested = false
                    showAgentChatDialog()
                }
            } else {
                agentJob = null
                handleAgentResult(currentTrip, result)
            }
        }
    }

    private fun runAgentWork(currentTrip: TripRequestData, requestedChange: String?) {
        if (agentJob?.isActive == true) return
        showAgentProgress(AgentTaskProgress(getString(R.string.pending), "STARTING"))
        agentJob = lifecycleScope.launch {
            val result = agentTripPlanner.generateOrResume(
                trip = currentTrip,
                requestedChange = requestedChange,
                onProgress = ::showAgentProgress,
            )
            agentJob = null
            handleAgentResult(currentTrip, result)
        }
    }

    private fun handleAgentResult(currentTrip: TripRequestData, result: AgentPollResult) {
        when (result) {
            AgentPollResult.ItineraryReady -> {
                pendingAgentRequest = null
                hideAgentTaskStatus()
                Snackbar.make(binding.root, R.string.agent_itinerary_updated, Snackbar.LENGTH_SHORT).show()
                loadTrip()
            }

            is AgentPollResult.NeedsMoreInfo -> {
                pendingAgentRequest = null
                val detail = result.clarifyingQuestion
                    ?: result.missingFields.joinToString().takeIf(String::isNotBlank)
                    ?: getString(R.string.trip_validation_failed)
                showAgentTaskMessage(
                    getString(R.string.agent_task_needs_info_format, detail),
                    retry = ::showAgentChatDialog,
                )
            }

            is AgentPollResult.TaskFailed -> {
                val message = result.message?.let {
                    getString(R.string.agent_task_failed_format, it)
                } ?: getString(R.string.agent_itinerary_failed)
                showAgentTaskMessage(
                    message,
                    retry = pendingAgentRequest?.let { request ->
                        { runAgentWork(currentTrip, request) }
                    } ?: ::showAgentChatDialog,
                )
            }

            AgentPollResult.TimedOut -> showAgentTaskMessage(
                getString(R.string.agent_itinerary_timeout),
                retry = { runAgentWork(currentTrip, pendingAgentRequest) },
            )

            is AgentPollResult.RequestFailure -> showAgentTaskMessage(
                tripMessageFor(result.failure),
                retry = when {
                    result.failure.isTripRetryable() -> {
                        { runAgentWork(currentTrip, pendingAgentRequest) }
                    }
                    result.failure.kind == ApiFailureKind.NOT_FOUND -> {
                        pendingAgentRequest?.let { request ->
                            { runAgentWork(currentTrip, request) }
                        } ?: ::showAgentChatDialog
                    }
                    else -> null
                },
            )

            AgentPollResult.InvalidResponse -> showAgentTaskMessage(
                getString(R.string.agent_itinerary_invalid_response),
                retry = pendingAgentRequest?.let { request ->
                    { runAgentWork(currentTrip, request) }
                } ?: ::showAgentChatDialog,
            )
        }
        val isCancelled = currentTrip.remoteStatus.equals("CANCELLED", ignoreCase = true)
        binding.requestModificationButton.isEnabled = !isCancelled && agentJob?.isActive != true
    }

    private fun showAgentProgress(progress: AgentTaskProgress) {
        binding.agentTaskContainer.isVisible = true
        binding.agentTaskStatus.text = getString(
            R.string.agent_task_accepted_format,
            progress.taskId,
            progress.status,
        )
        binding.agentTaskRetryButton.isVisible = false
        binding.requestModificationButton.isEnabled = false
        agentTaskRetryAction = null
    }

    private fun showAgentTaskMessage(message: String, retry: (() -> Unit)?) {
        binding.agentTaskContainer.isVisible = true
        binding.agentTaskStatus.text = message
        binding.agentTaskRetryButton.isVisible = retry != null
        agentTaskRetryAction = retry
    }

    private fun hideAgentTaskStatus() {
        binding.agentTaskContainer.isVisible = false
        binding.agentTaskRetryButton.isVisible = false
        agentTaskRetryAction = null
    }

    private fun setActionsEnabled(enabled: Boolean) {
        binding.editTripButton.isEnabled = enabled
        binding.cancelTripButton.isEnabled = enabled
        binding.requestModificationButton.isEnabled = enabled
        binding.addExpenseButton.isEnabled = enabled
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PENDING_AGENT_REQUEST, pendingAgentRequest)
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
        private const val EXTRA_TRIP_ID = "trip_id"
        private const val EXTRA_OPEN_AGENT_DIALOG = "open_agent_dialog"
        private const val INVALID_TRIP_ID = -1L
        private const val STATE_PENDING_AGENT_REQUEST = "pending_agent_request"
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)

        fun createIntent(context: Context, tripId: Long): Intent {
            return Intent(context, TripDetailActivity::class.java).putExtra(EXTRA_TRIP_ID, tripId)
        }

        fun createIntent(context: Context, tripRequest: TripRequestData): Intent {
            return Intent(context, TripDetailActivity::class.java).putExtra(TripRequestData.EXTRA_KEY, tripRequest)
        }

        fun createAgentRequestIntent(context: Context, tripId: Long): Intent {
            return createIntent(context, tripId).putExtra(EXTRA_OPEN_AGENT_DIALOG, true)
        }
    }
}
