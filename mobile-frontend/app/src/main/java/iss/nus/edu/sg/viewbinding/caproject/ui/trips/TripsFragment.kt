package iss.nus.edu.sg.viewbinding.caproject.ui.trips

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import iss.nus.edu.sg.viewbinding.caproject.data.mock.CurrentTripStore
import iss.nus.edu.sg.viewbinding.caproject.data.mock.MockTravelData
import iss.nus.edu.sg.viewbinding.caproject.databinding.FragmentTripsBinding

class TripsFragment : Fragment() {

    private var _binding: FragmentTripsBinding? = null
    private val binding get() = requireNotNull(_binding)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentTripsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.newTripButton.setOnClickListener {
            startActivity(Intent(requireContext(), TripRequestActivity::class.java))
        }
        binding.tripCard.setOnClickListener {
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
        binding.tripTitle.text = trip.title
        binding.tripDates.text = trip.dates
        binding.tripRoute.text = trip.route
        binding.tripBudget.text = trip.budgetSummary
        binding.tripStatus.text = trip.status
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
