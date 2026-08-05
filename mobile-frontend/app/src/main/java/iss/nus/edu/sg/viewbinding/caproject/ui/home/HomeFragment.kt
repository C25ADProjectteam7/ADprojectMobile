package iss.nus.edu.sg.viewbinding.caproject.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import iss.nus.edu.sg.viewbinding.caproject.data.mock.CurrentTripStore
import iss.nus.edu.sg.viewbinding.caproject.data.mock.MockTravelData
import iss.nus.edu.sg.viewbinding.caproject.databinding.FragmentHomeBinding
import iss.nus.edu.sg.viewbinding.caproject.ui.trips.TripDetailActivity
import iss.nus.edu.sg.viewbinding.caproject.ui.trips.TripRequestActivity

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = requireNotNull(_binding)

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
    }

    override fun onResume() {
        super.onResume()
        val trip = MockTravelData.tripSummaryFor(
            CurrentTripStore.currentTrip,
            CurrentTripStore.isMockBooked,
        )
        binding.upcomingTripTitle.text = trip.title
        binding.upcomingTripDates.text = trip.dates
        binding.upcomingTripRoute.text = trip.route
        binding.upcomingTripStatus.text = trip.status
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
