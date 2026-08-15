package iss.nus.edu.sg.viewbinding.caproject.ui.claims

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import iss.nus.edu.sg.viewbinding.caproject.BuildConfig
import iss.nus.edu.sg.viewbinding.caproject.R
import iss.nus.edu.sg.viewbinding.caproject.data.repository.ExpenseRepository
import iss.nus.edu.sg.viewbinding.caproject.data.repository.TripRepository
import iss.nus.edu.sg.viewbinding.caproject.databinding.ActivityClaimStatusBinding
import iss.nus.edu.sg.viewbinding.caproject.model.ExpenseRecord
import iss.nus.edu.sg.viewbinding.caproject.model.TripRequestData
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult
import iss.nus.edu.sg.viewbinding.caproject.ui.auth.AuthenticatedActivity
import iss.nus.edu.sg.viewbinding.caproject.ui.expense.AddExpenseActivity
import iss.nus.edu.sg.viewbinding.caproject.ui.expense.ExpenseUiFormatter
import iss.nus.edu.sg.viewbinding.caproject.ui.expense.expenseMessageFor
import iss.nus.edu.sg.viewbinding.caproject.ui.expense.isExpenseRetryable
import iss.nus.edu.sg.viewbinding.caproject.ui.main.MainActivity
import kotlinx.coroutines.launch

class ClaimStatusActivity : AuthenticatedActivity() {

    private lateinit var binding: ActivityClaimStatusBinding
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var tripRepository: TripRepository
    private var expenseId: Long = 0
    private var expenseTrip: TripRequestData? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        binding = ActivityClaimStatusBinding.inflate(layoutInflater)
        setContentView(binding.root)
        expenseRepository = ExpenseRepository.create(this)
        tripRepository = TripRepository.create(this)
        expenseId = intent.getLongExtra(EXTRA_EXPENSE_ID, 0)

        binding.backButton.setOnClickListener { finish() }
        binding.claimRetryButton.setOnClickListener { loadExpense() }
        binding.addAnotherExpenseButton.setOnClickListener {
            expenseTrip?.let { startActivity(AddExpenseActivity.createIntent(this, it)) }
        }
        setupBottomNavigation()

        if (expenseId <= 0) {
            showFailure(getString(R.string.expense_not_found), retry = false)
        } else {
            loadExpense()
        }
    }

    private fun loadExpense() {
        showLoading()
        lifecycleScope.launch {
            when (val result = expenseRepository.getExpense(expenseId)) {
                is ApiResult.Success -> loadTripAndShow(result.value)
                is ApiResult.Failure -> showFailure(
                    message = expenseMessageFor(result),
                    retry = result.isExpenseRetryable(),
                )
            }
        }
    }

    private suspend fun loadTripAndShow(expense: ExpenseRecord) {
        when (val tripResult = tripRepository.getTrip(expense.tripId)) {
            is ApiResult.Success -> {
                expenseTrip = tripResult.value
                bindExpense(expense, tripResult.value.city)
            }
            is ApiResult.Failure -> {
                expenseTrip = null
                bindExpense(expense, getString(R.string.trips))
            }
        }
    }

    private fun bindExpense(expense: ExpenseRecord, destination: String) {
        binding.claimLoading.isVisible = false
        binding.claimStateContainer.isVisible = false
        binding.claimContent.isVisible = true
        binding.claimSubtitle.text = getString(
            R.string.claim_reference_backend_format,
            destination,
            expense.id,
        )
        binding.claimAmount.text = ExpenseUiFormatter.amount(expense.amount, expense.currency)
        binding.claimMerchant.text = expense.merchant
        binding.claimPolicy.text = getString(
            R.string.claim_category_format,
            ExpenseUiFormatter.category(expense.category),
        )
        binding.claimSubmittedDate.text = ExpenseUiFormatter.submittedAt(expense)
        ExpenseUiFormatter.bindStatus(this, binding.claimStatus, expense.status)
        binding.claimDecisionDetail.setText(
            when (expense.status) {
                ExpenseRecord.STATUS_APPROVED -> R.string.claim_decision_approved
                ExpenseRecord.STATUS_REJECTED -> R.string.claim_decision_rejected
                ExpenseRecord.STATUS_NEEDS_INFO -> R.string.claim_decision_needs_info
                else -> R.string.claim_decision_pending
            },
        )
        // Finance's note (approval opinion / request for more info) - shown
        // whenever a reviewer wrote one.
        val note = expense.approvalOpinion?.takeIf(String::isNotBlank)
        binding.claimFinanceNote.isVisible = note != null
        binding.claimFinanceNote.text = note
            ?.let { getString(R.string.claim_finance_note_format, it) }
        bindReceipt(expense.receiptUrl)
        binding.addAnotherExpenseButton.isEnabled = expenseTrip != null
    }

    private fun bindReceipt(receiptUrl: String?) {
        val url = receiptUrl?.takeIf(String::isNotBlank)
        binding.claimReceipt.text = url ?: getString(R.string.receipt_not_available)
        binding.openReceiptButton.isVisible = url != null
        binding.openReceiptButton.setOnClickListener {
            url?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(resolveReceiptUrl(it)))) }
        }
    }

    private fun resolveReceiptUrl(receiptUrl: String): String {
        if (receiptUrl.startsWith("http://") || receiptUrl.startsWith("https://")) return receiptUrl
        return BuildConfig.API_BASE_URL.trimEnd('/') + "/" + receiptUrl.trimStart('/')
    }

    private fun showLoading() {
        binding.claimLoading.isVisible = true
        binding.claimStateContainer.isVisible = false
        binding.claimContent.isVisible = false
    }

    private fun showFailure(message: String, retry: Boolean) {
        binding.claimLoading.isVisible = false
        binding.claimContent.isVisible = false
        binding.claimStateContainer.isVisible = true
        binding.claimStateMessage.text = message
        binding.claimRetryButton.isVisible = retry
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

    companion object {
        const val EXTRA_EXPENSE_ID = "expense_id"

        fun createIntent(context: Context, expenseId: Long): Intent {
            return Intent(context, ClaimStatusActivity::class.java).apply {
                putExtra(EXTRA_EXPENSE_ID, expenseId)
            }
        }
    }
}
