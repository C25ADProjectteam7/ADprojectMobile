package iss.nus.edu.sg.viewbinding.caproject.ui.expense

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
import iss.nus.edu.sg.viewbinding.caproject.databinding.FragmentExpensesBinding
import iss.nus.edu.sg.viewbinding.caproject.databinding.ItemExpenseRecordBinding
import iss.nus.edu.sg.viewbinding.caproject.model.ExpenseRecord
import iss.nus.edu.sg.viewbinding.caproject.model.TripRequestData
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult
import kotlinx.coroutines.launch

class ExpensesFragment : Fragment() {

    private var _binding: FragmentExpensesBinding? = null
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
        _binding = FragmentExpensesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.addExpenseButton.setOnClickListener { openAddExpense() }
        binding.emptyAddExpenseButton.setOnClickListener { openAddExpense() }
        binding.expenseRetryButton.setOnClickListener { loadExpenses() }
    }

    override fun onResume() {
        super.onResume()
        loadExpenses()
    }

    private fun loadExpenses() {
        binding.expenseLoading.isVisible = true
        binding.expenseStateContainer.isVisible = false
        binding.recentExpensesLabel.isVisible = false
        binding.expenseList.removeAllViews()

        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = expenseRepository.getExpenses()) {
                is ApiResult.Success -> showExpenses(result.value)
                is ApiResult.Failure -> showFailure(result)
            }
        }
    }

    private fun showExpenses(expenses: List<ExpenseRecord>) {
        binding.expenseLoading.isVisible = false
        if (expenses.isEmpty()) {
            binding.expenseStateContainer.isVisible = true
            binding.expenseStateMessage.setText(R.string.no_expenses_body)
            binding.expenseRetryButton.isVisible = false
            binding.emptyAddExpenseButton.isVisible = true
            return
        }
        binding.recentExpensesLabel.isVisible = true
        expenses.forEach(::addExpenseCard)
    }

    private fun addExpenseCard(expense: ExpenseRecord) {
        val item = ItemExpenseRecordBinding.inflate(layoutInflater, binding.expenseList, false)
        item.expenseMerchant.text = expense.merchant
        item.expenseDetails.text = ExpenseUiFormatter.details(expense)
        item.expenseAmount.text = ExpenseUiFormatter.amount(expense.amount, expense.currency)
        item.expenseReceipt.text = getString(
            R.string.expense_receipt_format,
            expense.receiptName.ifBlank { getString(R.string.receipt_not_available) },
        )
        ExpenseUiFormatter.bindStatus(requireContext(), item.expenseStatus, expense.status)
        item.expenseAction.setText(R.string.view_claim_status)
        item.root.setOnClickListener { openClaim(expense.id) }
        binding.expenseList.addView(item.root)
    }

    private fun showFailure(failure: ApiResult.Failure) {
        binding.expenseLoading.isVisible = false
        binding.expenseStateContainer.isVisible = true
        binding.expenseStateMessage.text = requireContext().expenseMessageFor(failure)
        binding.expenseRetryButton.isVisible = failure.isExpenseRetryable()
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
        binding.addExpenseButton.isEnabled = enabled
        binding.emptyAddExpenseButton.isEnabled = enabled
    }

    private fun launchAddExpense(trip: TripRequestData) {
        setAddButtonsEnabled(true)
        startActivity(AddExpenseActivity.createIntent(requireContext(), trip))
    }

    private fun openClaim(expenseId: Long) {
        startActivity(iss.nus.edu.sg.viewbinding.caproject.ui.claims.ClaimStatusActivity.createIntent(
            requireContext(),
            expenseId,
        ))
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
