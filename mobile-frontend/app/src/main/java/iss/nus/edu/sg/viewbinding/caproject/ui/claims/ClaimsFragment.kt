package iss.nus.edu.sg.viewbinding.caproject.ui.claims

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import iss.nus.edu.sg.viewbinding.caproject.R
import iss.nus.edu.sg.viewbinding.caproject.data.mock.CurrentExpenseStore
import iss.nus.edu.sg.viewbinding.caproject.data.mock.CurrentTripStore
import iss.nus.edu.sg.viewbinding.caproject.databinding.FragmentClaimsBinding
import iss.nus.edu.sg.viewbinding.caproject.model.PolicyResult
import iss.nus.edu.sg.viewbinding.caproject.ui.expense.AddExpenseActivity
import java.text.NumberFormat
import java.util.Locale

class ClaimsFragment : Fragment() {

    private var _binding: FragmentClaimsBinding? = null
    private val binding get() = requireNotNull(_binding)

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
        binding.claimCard.setOnClickListener { openLatestClaim() }
        binding.viewClaimStatusButton.setOnClickListener { openLatestClaim() }
    }

    override fun onResume() {
        super.onResume()
        val expense = CurrentExpenseStore.latestExpense
        val hasClaim = expense != null
        binding.claimPendingMessage.isVisible = hasClaim
        binding.claimCard.isVisible = hasClaim
        binding.addAnotherExpenseButton.isVisible = hasClaim
        binding.emptyState.isVisible = !hasClaim
        if (expense == null) return

        binding.claimAmount.text = formatSgd(expense.amount)
        binding.claimReference.text = getString(
            R.string.claim_reference_format,
            expense.destination,
            expense.claimReference,
        )
        binding.claimMerchant.text = expense.merchant
        binding.claimReceipt.text = expense.receiptName
        binding.claimPolicy.text = getString(
            if (expense.policyResult == PolicyResult.WITHIN_POLICY) {
                R.string.within_policy
            } else {
                R.string.review_required
            },
        )
        if (expense.policyResult == PolicyResult.REVIEW_REQUIRED) {
            binding.claimPolicy.setTextColor(requireContext().getColor(R.color.travel_gold_dark))
            binding.claimPolicy.setBackgroundResource(R.drawable.bg_status_gold)
        } else {
            binding.claimPolicy.setTextColor(requireContext().getColor(R.color.travel_green))
            binding.claimPolicy.setBackgroundResource(R.drawable.bg_status_green)
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
}
