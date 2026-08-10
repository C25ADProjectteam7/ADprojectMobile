package iss.nus.edu.sg.viewbinding.caproject.ui.claims

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import iss.nus.edu.sg.viewbinding.caproject.R
import iss.nus.edu.sg.viewbinding.caproject.data.mock.CurrentExpenseStore
import iss.nus.edu.sg.viewbinding.caproject.data.mock.CurrentTripStore
import iss.nus.edu.sg.viewbinding.caproject.data.mock.MockExpenseData
import iss.nus.edu.sg.viewbinding.caproject.databinding.ActivityClaimStatusBinding
import iss.nus.edu.sg.viewbinding.caproject.model.PolicyResult
import iss.nus.edu.sg.viewbinding.caproject.ui.expense.AddExpenseActivity
import iss.nus.edu.sg.viewbinding.caproject.ui.main.MainActivity
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale

class ClaimStatusActivity : AppCompatActivity() {

    private lateinit var binding: ActivityClaimStatusBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        binding = ActivityClaimStatusBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val expense = CurrentExpenseStore.latestExpense
        if (expense == null) {
            finish()
            return
        }
        val claim = MockExpenseData.claimFor(expense)

        binding.claimSubtitle.text = getString(
            R.string.claim_status_subtitle_format,
            claim.destination,
            claim.reference,
        )
        binding.claimAmount.text = formatSgd(claim.amount)
        binding.claimMerchant.text = claim.merchant
        binding.claimReceipt.text = claim.receiptName
        binding.claimStatus.text = getString(R.string.under_review)
        binding.claimPolicy.text = getString(
            if (claim.policyResult == PolicyResult.WITHIN_POLICY) {
                R.string.within_policy
            } else {
                R.string.review_required
            },
        )
        if (claim.policyResult == PolicyResult.REVIEW_REQUIRED) {
            binding.claimPolicy.setTextColor(getColor(R.color.travel_gold_dark))
            binding.claimPolicy.setBackgroundResource(R.drawable.bg_status_gold)
        }
        binding.claimSubmittedDate.text = claim.submittedAt.format(SUBMITTED_FORMATTER)
        binding.receiptPreview.isVisible = runCatching {
            binding.receiptPreview.setImageURI(Uri.parse(expense.receiptUri))
            true
        }.getOrDefault(false)

        binding.backButton.setOnClickListener { finish() }
        binding.addAnotherExpenseButton.setOnClickListener {
            startActivity(AddExpenseActivity.createIntent(this, CurrentTripStore.currentTrip))
        }
        setupBottomNavigation()
    }

    private fun setupBottomNavigation() {
        binding.claimStatusBottomNavigation.selectedItemId = R.id.navigation_claims
        binding.claimStatusBottomNavigation.setOnItemSelectedListener { item ->
            openMainTab(item.itemId)
            true
        }
        binding.claimStatusBottomNavigation.setOnItemReselectedListener {
            openMainTab(R.id.navigation_claims)
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

    private fun formatSgd(value: Double): String {
        val formatter = NumberFormat.getNumberInstance(Locale.ENGLISH).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        return "S$${formatter.format(value)}"
    }

    companion object {
        const val EXTRA_CLAIM_REFERENCE = "claim_reference"
        private val SUBMITTED_FORMATTER = DateTimeFormatter.ofPattern("d MMM · HH:mm", Locale.ENGLISH)

        fun createIntent(context: Context, claimReference: String): Intent {
            return Intent(context, ClaimStatusActivity::class.java).apply {
                putExtra(EXTRA_CLAIM_REFERENCE, claimReference)
            }
        }
    }
}
