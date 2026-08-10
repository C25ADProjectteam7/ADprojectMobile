package iss.nus.edu.sg.viewbinding.caproject.ui.expense

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import iss.nus.edu.sg.viewbinding.caproject.R
import iss.nus.edu.sg.viewbinding.caproject.data.mock.CurrentExpenseStore
import iss.nus.edu.sg.viewbinding.caproject.data.mock.CurrentTripStore
import iss.nus.edu.sg.viewbinding.caproject.databinding.ActivityAddExpenseBinding
import iss.nus.edu.sg.viewbinding.caproject.model.TripRequestData
import iss.nus.edu.sg.viewbinding.caproject.ui.claims.ClaimStatusActivity
import iss.nus.edu.sg.viewbinding.caproject.ui.main.MainActivity
import iss.nus.edu.sg.viewbinding.caproject.validation.ExpenseValidator
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class AddExpenseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddExpenseBinding
    private lateinit var tripRequest: TripRequestData
    private var selectedDate: LocalDate? = null
    private var receiptUri: Uri? = null
    private var receiptName: String = ""

    private val receiptPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) bindSelectedReceipt(uri)
    }

    private val finishMockSubmission = Runnable { saveAndOpenClaim() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        binding = ActivityAddExpenseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tripRequest = IntentCompat.getSerializableExtra(
            intent,
            TripRequestData.EXTRA_KEY,
            TripRequestData::class.java,
        ) ?: CurrentTripStore.currentTrip
        selectedDate = tripRequest.startDate

        bindTrip()
        setupCategoryOptions()
        setupActions()
        setupValidationClearing()
        setupBottomNavigation()
    }

    private fun bindTrip() {
        binding.addExpenseSubtitle.text = getString(
            R.string.add_expense_subtitle_format,
            tripRequest.city,
        )
        binding.dateInput.setText(selectedDate?.format(DATE_FORMATTER))
    }

    private fun setupCategoryOptions() {
        val categories = resources.getStringArray(R.array.expense_categories)
        binding.categoryInput.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, categories),
        )
    }

    private fun setupActions() {
        binding.backButton.setOnClickListener { finish() }
        binding.receiptPickerButton.setOnClickListener {
            receiptPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
        binding.dateInput.setOnClickListener {
            val date = selectedDate ?: tripRequest.startDate
            DatePickerDialog(
                this,
                { _, year, month, day ->
                    selectedDate = LocalDate.of(year, month + 1, day)
                    binding.dateInput.setText(selectedDate?.format(DATE_FORMATTER))
                    binding.dateInputLayout.error = null
                },
                date.year,
                date.monthValue - 1,
                date.dayOfMonth,
            ).show()
        }
        binding.submitExpenseButton.setOnClickListener { validateAndSubmit() }
        binding.addExpenseRoot.setOnClickListener { hideKeyboard() }
    }

    private fun setupValidationClearing() {
        binding.merchantInput.doAfterTextChanged { binding.merchantInputLayout.error = null }
        binding.amountInput.doAfterTextChanged { binding.amountInputLayout.error = null }
        binding.categoryInput.doAfterTextChanged { binding.categoryInputLayout.error = null }
    }

    private fun bindSelectedReceipt(uri: Uri) {
        receiptUri = uri
        receiptName = queryDisplayName(uri) ?: getString(R.string.selected_receipt_fallback)
        binding.receiptPreview.setImageURI(uri)
        binding.receiptPreview.isVisible = true
        binding.receiptSelectedName.text = receiptName
        binding.receiptSelectedName.isVisible = true
        binding.receiptError.isVisible = false
        binding.receiptPickerButton.setText(R.string.replace_receipt_image)
    }

    private fun validateAndSubmit() {
        hideKeyboard()
        val merchant = binding.merchantInput.text?.toString().orEmpty().trim()
        val amountText = binding.amountInput.text?.toString().orEmpty().trim()
        val category = binding.categoryInput.text?.toString().orEmpty().trim()
        var isValid = true

        binding.receiptError.isVisible = receiptUri == null
        if (receiptUri == null) isValid = false

        binding.merchantInputLayout.error = if (merchant.isBlank()) {
            isValid = false
            getString(R.string.merchant_required)
        } else null

        binding.dateInputLayout.error = if (
            !ExpenseValidator.isDateWithinTrip(selectedDate, tripRequest.startDate, tripRequest.endDate)
        ) {
            isValid = false
            getString(R.string.expense_date_invalid)
        } else null

        binding.amountInputLayout.error = when {
            amountText.isBlank() -> {
                isValid = false
                getString(R.string.amount_required)
            }
            !ExpenseValidator.isPositiveAmount(amountText) -> {
                isValid = false
                getString(R.string.amount_invalid)
            }
            else -> null
        }

        binding.categoryInputLayout.error = if (category.isBlank()) {
            isValid = false
            getString(R.string.category_required)
        } else null

        if (!isValid) return

        binding.submissionProgress.isVisible = true
        binding.submitExpenseButton.isEnabled = false
        binding.submitExpenseButton.setText(R.string.submitting_expense)
        binding.submitExpenseButton.postDelayed(finishMockSubmission, MOCK_SUBMISSION_DELAY_MS)
    }

    private fun saveAndOpenClaim() {
        val expense = CurrentExpenseStore.submit(
            destination = tripRequest.city,
            merchant = binding.merchantInput.text?.toString().orEmpty().trim(),
            date = requireNotNull(selectedDate),
            amount = binding.amountInput.text?.toString().orEmpty().replace(",", "").toDouble(),
            category = binding.categoryInput.text?.toString().orEmpty().trim(),
            notes = binding.notesInput.text?.toString().orEmpty().trim(),
            receiptUri = requireNotNull(receiptUri).toString(),
            receiptName = receiptName,
        )
        startActivity(ClaimStatusActivity.createIntent(this, expense.claimReference))
        finish()
    }

    private fun queryDisplayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor?.moveToFirst() == true) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else null
        } finally {
            cursor?.close()
        }
    }

    private fun hideKeyboard() {
        currentFocus?.let { focusedView ->
            val keyboard = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            keyboard.hideSoftInputFromWindow(focusedView.windowToken, 0)
            focusedView.clearFocus()
        }
    }

    private fun setupBottomNavigation() {
        binding.addExpenseBottomNavigation.selectedItemId = R.id.navigation_expenses
        binding.addExpenseBottomNavigation.setOnItemSelectedListener { item ->
            openMainTab(item.itemId)
            true
        }
        binding.addExpenseBottomNavigation.setOnItemReselectedListener {
            openMainTab(R.id.navigation_expenses)
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

    override fun onDestroy() {
        if (::binding.isInitialized) {
            binding.submitExpenseButton.removeCallbacks(finishMockSubmission)
        }
        super.onDestroy()
    }

    companion object {
        private const val MOCK_SUBMISSION_DELAY_MS = 700L
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

        fun createIntent(context: Context, tripRequest: TripRequestData): Intent {
            return Intent(context, AddExpenseActivity::class.java).apply {
                putExtra(TripRequestData.EXTRA_KEY, tripRequest)
            }
        }
    }
}
