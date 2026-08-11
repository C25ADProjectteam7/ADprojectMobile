package iss.nus.edu.sg.viewbinding.caproject.ui.claims

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import iss.nus.edu.sg.viewbinding.caproject.R
import iss.nus.edu.sg.viewbinding.caproject.data.local.CurrentTripStore
import iss.nus.edu.sg.viewbinding.caproject.data.repository.ExpenseRepository
import iss.nus.edu.sg.viewbinding.caproject.data.repository.TripRepository
import iss.nus.edu.sg.viewbinding.caproject.databinding.FragmentClaimsBinding
import iss.nus.edu.sg.viewbinding.caproject.databinding.ItemExpenseRecordBinding
import iss.nus.edu.sg.viewbinding.caproject.model.ExpenseRecord
import iss.nus.edu.sg.viewbinding.caproject.model.TripRequestData
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult
import iss.nus.edu.sg.viewbinding.caproject.ui.expense.AddExpenseActivity
import iss.nus.edu.sg.viewbinding.caproject.ui.expense.ExpenseUiFormatter
import iss.nus.edu.sg.viewbinding.caproject.ui.expense.expenseMessageFor
import iss.nus.edu.sg.viewbinding.caproject.ui.expense.isExpenseRetryable
import kotlinx.coroutines.launch

class ClaimsFragment : Fragment() {

    private var _binding: FragmentClaimsBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val expenseRepository by lazy(LazyThreadSafetyMode.NONE) {
        ExpenseRepository.create(requireContext())
    }
    private val tripRepository by lazy(LazyThreadSafetyMode.NONE) {
        TripRepository.create(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentClaimsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.emptyAddExpenseButton.setOnClickListener { openAddExpense() }
        binding.addAnotherExpenseButton.setOnClickListener { openAddExpense() }
        binding.claimRetryButton.setOnClickListener { loadClaims() }
    }

    override fun onResume() {
        super.onResume()
        loadClaims()
    }

    private fun loadClaims() {
        binding.claimLoading.isVisible = true
        binding.claimPendingMessage.isVisible = false
        binding.claimStateContainer.isVisible = false
        binding.addAnotherExpenseButton.isVisible = false
        binding.claimList.removeAllViews()
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = expenseRepository.getExpenses()) {
                is ApiResult.Success -> showClaims(result.value)
                is ApiResult.Failure -> showFailure(result)
            }
        }
    }

    private fun showClaims(expenses: List<ExpenseRecord>) {
        binding.claimLoading.isVisible = false
        if (expenses.isEmpty()) {
            binding.claimStateContainer.isVisible = true
            binding.claimStateMessage.setText(R.string.no_claims_body)
            binding.claimRetryButton.isVisible = false
            binding.emptyAddExpenseButton.isVisible = true
            return
        }
        binding.claimPendingMessage.isVisible = expenses.any {
            it.status == ExpenseRecord.STATUS_SUBMITTED
        }
        binding.addAnotherExpenseButton.isVisible = true
        expenses.forEach(::addClaimCard)
    }

    private fun addClaimCard(expense: ExpenseRecord) {
        val item = ItemExpenseRecordBinding.inflate(layoutInflater, binding.claimList, false)
        item.expenseMerchant.text = getString(R.string.expense_reference_format, expense.id, expense.tripId)
        item.expenseDetails.text = expense.merchant
        item.expenseAmount.text = ExpenseUiFormatter.amount(expense.amount, expense.currency)
        item.expenseReceipt.text = ExpenseUiFormatter.submittedAt(expense)
        ExpenseUiFormatter.bindStatus(requireContext(), item.expenseStatus, expense.status)
        item.expenseAction.setText(R.string.view_claim_status)
        item.root.setOnClickListener {
            startActivity(ClaimStatusActivity.createIntent(requireContext(), expense.id))
        }
        binding.claimList.addView(item.root)
    }

    private fun showFailure(failure: ApiResult.Failure) {
        binding.claimLoading.isVisible = false
        binding.claimStateContainer.isVisible = true
        binding.claimStateMessage.text = requireContext().expenseMessageFor(failure)
        binding.claimRetryButton.isVisible = failure.isExpenseRetryable()
        binding.emptyAddExpenseButton.isVisible = false
    }

    private fun openAddExpense() {
        val current = CurrentTripStore.currentTrip?.takeIf {
            it.remoteId != null && !it.remoteStatus.equals("CANCELLED", ignoreCase = true)
        }
        if (current != null) {
            launchAddExpense(current)
            return
        }
        setAddButtonsEnabled(false)
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = tripRepository.getTrips()) {
                is ApiResult.Success -> {
                    val trip = result.value
                        .filterNot { it.remoteStatus.equals("CANCELLED", ignoreCase = true) }
                        .maxByOrNull(TripRequestData::endDate)
                    if (trip == null) {
                        Snackbar.make(binding.root, R.string.expense_trip_required, Snackbar.LENGTH_LONG).show()
                        setAddButtonsEnabled(true)
                    } else {
                        CurrentTripStore.saveRequest(trip)
                        launchAddExpense(trip)
                    }
                }
                is ApiResult.Failure -> {
                    Snackbar.make(
                        binding.root,
                        requireContext().expenseMessageFor(result),
                        Snackbar.LENGTH_LONG,
                    ).show()
                    setAddButtonsEnabled(true)
                }
            }
        }
    }

    private fun setAddButtonsEnabled(enabled: Boolean) {
        binding.addAnotherExpenseButton.isEnabled = enabled
        binding.emptyAddExpenseButton.isEnabled = enabled
    }

    private fun launchAddExpense(trip: TripRequestData) {
        setAddButtonsEnabled(true)
        startActivity(AddExpenseActivity.createIntent(requireContext(), trip))
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
