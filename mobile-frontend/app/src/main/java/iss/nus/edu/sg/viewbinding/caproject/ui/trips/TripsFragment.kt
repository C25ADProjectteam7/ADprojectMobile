package iss.nus.edu.sg.viewbinding.caproject.ui.trips

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import iss.nus.edu.sg.viewbinding.caproject.R
import iss.nus.edu.sg.viewbinding.caproject.data.repository.TripRepository
import iss.nus.edu.sg.viewbinding.caproject.databinding.FragmentTripsBinding
import iss.nus.edu.sg.viewbinding.caproject.databinding.ItemTripSummaryBinding
import iss.nus.edu.sg.viewbinding.caproject.model.TripRequestData
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult
import kotlinx.coroutines.launch

class TripsFragment : Fragment() {

    private var _binding: FragmentTripsBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val tripRepository by lazy(LazyThreadSafetyMode.NONE) {
        TripRepository.create(requireContext())
    }

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
        binding.tripsRetryButton.setOnClickListener { loadTrips() }
    }

    override fun onResume() {
        super.onResume()
        loadTrips()
    }

    private fun loadTrips() {
        showLoading()
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = tripRepository.getTrips()) {
                is ApiResult.Success -> showTrips(result.value)
                is ApiResult.Failure -> showFailure(result)
            }
        }
    }

    private fun showLoading() {
        binding.tripsLoading.isVisible = true
        binding.tripsStateContainer.isVisible = false
        binding.tripsListContainer.isVisible = false
    }

    private fun showTrips(trips: List<TripRequestData>) {
        binding.tripsLoading.isVisible = false
        binding.tripsListContainer.removeAllViews()
        if (trips.isEmpty()) {
            showState(
                title = getString(R.string.no_trips_title),
                message = getString(R.string.no_trips_message),
                showRetry = false,
            )
            return
        }

        binding.tripsStateContainer.isVisible = false
        binding.tripsListContainer.isVisible = true
        trips.sortedByDescending { it.startDate }.forEach { trip ->
            val itemBinding = ItemTripSummaryBinding.inflate(
                layoutInflater,
                binding.tripsListContainer,
                false,
            )
            itemBinding.tripTitle.text = trip.displayTitle
            itemBinding.tripStatus.text = TripUiFormatter.status(trip)
            itemBinding.tripDates.text = TripUiFormatter.dates(trip)
            itemBinding.tripRoute.text = TripUiFormatter.route(trip)
            itemBinding.tripBudget.text = TripUiFormatter.budget(trip)
            itemBinding.root.setOnClickListener {
                startActivity(TripDetailActivity.createIntent(requireContext(), requireNotNull(trip.remoteId)))
            }
            binding.tripsListContainer.addView(itemBinding.root)
        }
    }

    private fun showFailure(failure: ApiResult.Failure) {
        binding.tripsLoading.isVisible = false
        showState(
            title = getString(R.string.trips_unavailable_title),
            message = requireContext().tripMessageFor(failure),
            showRetry = failure.isTripRetryable(),
        )
    }

    private fun showState(title: String, message: String, showRetry: Boolean) {
        binding.tripsListContainer.isVisible = false
        binding.tripsStateContainer.isVisible = true
        binding.tripsStateTitle.text = title
        binding.tripsStateMessage.text = message
        binding.tripsRetryButton.isVisible = showRetry
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
