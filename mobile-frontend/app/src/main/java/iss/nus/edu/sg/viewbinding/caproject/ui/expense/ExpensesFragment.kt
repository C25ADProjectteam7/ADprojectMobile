package iss.nus.edu.sg.viewbinding.caproject.ui.expense

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import iss.nus.edu.sg.viewbinding.caproject.R
import iss.nus.edu.sg.viewbinding.caproject.data.mock.CurrentExpenseStore
import iss.nus.edu.sg.viewbinding.caproject.data.mock.CurrentTripStore
import iss.nus.edu.sg.viewbinding.caproject.databinding.FragmentExpensesBinding
import iss.nus.edu.sg.viewbinding.caproject.model.PolicyResult
import iss.nus.edu.sg.viewbinding.caproject.ui.claims.ClaimStatusActivity
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale

class ExpensesFragment : Fragment() {

    private var _binding: FragmentExpensesBinding? = null
    private val binding get() = requireNotNull(_binding)

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
        binding.expenseCard.setOnClickListener { openLatestClaim() }
        binding.viewClaimButton.setOnClickListener { openLatestClaim() }
    }

    override fun onResume() {
        super.onResume()
        val expense = CurrentExpenseStore.latestExpense
        binding.expenseCard.isVisible = expense != null
        binding.recentExpensesLabel.isVisible = expense != null
        binding.emptyState.isVisible = expense == null
        if (expense == null) return

        binding.expenseMerchant.text = expense.merchant
        binding.expenseDetails.text = getString(
            R.string.expense_details_format,
            expense.date.format(DATE_FORMATTER),
            expense.category,
        )
        binding.expenseAmount.text = formatSgd(expense.amount)
        binding.expenseReceipt.text = expense.receiptName
        binding.expensePolicy.text = getString(
            if (expense.policyResult == PolicyResult.WITHIN_POLICY) {
                R.string.within_policy
            } else {
                R.string.review_required
            },
        )
        if (expense.policyResult == PolicyResult.REVIEW_REQUIRED) {
            binding.expensePolicy.setTextColor(requireContext().getColor(R.color.travel_gold_dark))
            binding.expensePolicy.setBackgroundResource(R.drawable.bg_status_gold)
        } else {
            binding.expensePolicy.setTextColor(requireContext().getColor(R.color.travel_green))
            binding.expensePolicy.setBackgroundResource(R.drawable.bg_status_green)
        }
    }

    private fun openAddExpense() {
        startActivity(
            AddExpenseActivity.createIntent(requireContext(), CurrentTripStore.currentTrip),
        )
    }

    private fun openLatestClaim() {
        val expense = CurrentExpenseStore.latestExpense ?: return
        startActivity(ClaimStatusActivity.createIntent(requireContext(), expense.claimReference))
    }

    private fun formatSgd(value: Double): String {
        val formatter = NumberFormat.getNumberInstance(Locale.ENGLISH).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        return "S$${formatter.format(value)}"
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    }
}
