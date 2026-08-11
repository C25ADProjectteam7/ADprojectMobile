package iss.nus.edu.sg.viewbinding.caproject.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import iss.nus.edu.sg.viewbinding.caproject.R
import iss.nus.edu.sg.viewbinding.caproject.data.repository.AuthRepository
import iss.nus.edu.sg.viewbinding.caproject.data.repository.TripRepository
import iss.nus.edu.sg.viewbinding.caproject.databinding.FragmentHomeBinding
import iss.nus.edu.sg.viewbinding.caproject.model.TripRequestData
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult
import iss.nus.edu.sg.viewbinding.caproject.ui.auth.LoginActivity
import iss.nus.edu.sg.viewbinding.caproject.ui.auth.ResetPasswordActivity
import iss.nus.edu.sg.viewbinding.caproject.ui.profile.ProfileActivity
import iss.nus.edu.sg.viewbinding.caproject.ui.trips.TripDetailActivity
import iss.nus.edu.sg.viewbinding.caproject.ui.trips.TripRequestActivity
import iss.nus.edu.sg.viewbinding.caproject.ui.trips.TripUiFormatter
import iss.nus.edu.sg.viewbinding.caproject.ui.trips.isTripRetryable
import iss.nus.edu.sg.viewbinding.caproject.ui.trips.tripMessageFor
import java.time.LocalDate
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val authRepository by lazy(LazyThreadSafetyMode.NONE) {
        AuthRepository.create(requireContext())
    }
    private val tripRepository by lazy(LazyThreadSafetyMode.NONE) {
        TripRepository.create(requireContext())
    }
    private var displayedTrip: TripRequestData? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.planTripButton.setOnClickListener {
            startActivity(Intent(requireContext(), TripRequestActivity::class.java))
        }
        binding.viewTripsButton.setOnClickListener {
            displayedTrip?.remoteId?.let { tripId ->
                startActivity(TripDetailActivity.createIntent(requireContext(), tripId))
            }
        }
        binding.homeTripRetryButton.setOnClickListener { loadUpcomingTrip() }
        binding.logoutButton.setOnClickListener { confirmLogout() }
        binding.resetPasswordButton.setOnClickListener {
            startActivity(Intent(requireContext(), ResetPasswordActivity::class.java))
        }
        binding.profileButton.setOnClickListener {
            startActivity(Intent(requireContext(), ProfileActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        authRepository.currentSession()?.let { session ->
            binding.homeGreeting.text = getString(R.string.home_greeting_format, session.username)
        }
        loadUpcomingTrip()
    }

    private fun loadUpcomingTrip() {
        displayedTrip = null
        binding.homeTripLoading.isVisible = true
        binding.upcomingTripCard.isVisible = false
        binding.homeTripStateContainer.isVisible = false

        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = tripRepository.getTrips()) {
                is ApiResult.Success -> showUpcomingTrip(result.value)
                is ApiResult.Failure -> showTripFailure(result)
            }
        }
    }

    private fun showUpcomingTrip(trips: List<TripRequestData>) {
        binding.homeTripLoading.isVisible = false
        val today = LocalDate.now()
        val upcomingTrip = trips
            .filter { !it.startDate.isBefore(today) && it.remoteStatus.uppercase() != "CANCELLED" }
            .minByOrNull { it.startDate }
            ?: trips.filter { it.remoteStatus.uppercase() != "CANCELLED" }.maxByOrNull { it.startDate }

        if (upcomingTrip == null) {
            showTripState(getString(R.string.no_upcoming_trip), showRetry = false)
            return
        }

        displayedTrip = upcomingTrip
        binding.upcomingTripCard.isVisible = true
        binding.homeTripStateContainer.isVisible = false
        binding.upcomingTripTitle.text = upcomingTrip.displayTitle
        binding.upcomingTripStatus.text = TripUiFormatter.status(upcomingTrip)
        binding.upcomingTripDates.text = TripUiFormatter.dates(upcomingTrip)
        binding.upcomingTripRoute.text = TripUiFormatter.route(upcomingTrip)
    }

    private fun showTripFailure(failure: ApiResult.Failure) {
        binding.homeTripLoading.isVisible = false
        showTripState(
            message = requireContext().tripMessageFor(failure),
            showRetry = failure.isTripRetryable(),
        )
    }

    private fun showTripState(message: String, showRetry: Boolean) {
        binding.upcomingTripCard.isVisible = false
        binding.homeTripStateContainer.isVisible = true
        binding.homeTripStateMessage.text = message
        binding.homeTripRetryButton.isVisible = showRetry
    }

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.logout)
            .setMessage(R.string.logout_confirmation)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.logout) { _, _ -> logout() }
            .show()
    }

    private fun logout() {
        authRepository.logout()
        startActivity(
            Intent(requireContext(), LoginActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            ),
        )
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
