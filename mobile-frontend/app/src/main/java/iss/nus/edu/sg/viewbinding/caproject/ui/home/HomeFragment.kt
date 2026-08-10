package iss.nus.edu.sg.viewbinding.caproject.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import iss.nus.edu.sg.viewbinding.caproject.R
import iss.nus.edu.sg.viewbinding.caproject.data.mock.CurrentTripStore
import iss.nus.edu.sg.viewbinding.caproject.data.mock.MockTravelData
import iss.nus.edu.sg.viewbinding.caproject.data.repository.AuthRepository
import iss.nus.edu.sg.viewbinding.caproject.databinding.FragmentHomeBinding
import iss.nus.edu.sg.viewbinding.caproject.ui.auth.LoginActivity
import iss.nus.edu.sg.viewbinding.caproject.ui.trips.TripDetailActivity
import iss.nus.edu.sg.viewbinding.caproject.ui.trips.TripRequestActivity

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val authRepository by lazy(LazyThreadSafetyMode.NONE) {
        AuthRepository.create(requireContext())
    }

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
            startActivity(
                TripDetailActivity.createIntent(requireContext(), CurrentTripStore.currentTrip),
            )
        }
        binding.logoutButton.setOnClickListener { confirmLogout() }
    }

    override fun onResume() {
        super.onResume()
        authRepository.currentSession()?.let { session ->
            binding.homeGreeting.text = getString(R.string.home_greeting_format, session.username)
        }
        val trip = MockTravelData.tripSummaryFor(
            CurrentTripStore.currentTrip,
            CurrentTripStore.isMockBooked,
        )
        binding.upcomingTripTitle.text = trip.title
        binding.upcomingTripDates.text = trip.dates
        binding.upcomingTripRoute.text = trip.route
        binding.upcomingTripStatus.text = trip.status
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
