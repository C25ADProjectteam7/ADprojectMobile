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
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import iss.nus.edu.sg.viewbinding.caproject.BuildConfig
import iss.nus.edu.sg.viewbinding.caproject.R
import iss.nus.edu.sg.viewbinding.caproject.data.local.CurrentTripStore
import iss.nus.edu.sg.viewbinding.caproject.data.repository.ExpenseDescriptionCodec
import iss.nus.edu.sg.viewbinding.caproject.data.repository.ExpenseRepository
import iss.nus.edu.sg.viewbinding.caproject.databinding.ActivityAddExpenseBinding
import iss.nus.edu.sg.viewbinding.caproject.model.ReceiptUpload
import iss.nus.edu.sg.viewbinding.caproject.model.TripRequestData
import iss.nus.edu.sg.viewbinding.caproject.network.ApiResult
import iss.nus.edu.sg.viewbinding.caproject.ui.auth.AuthenticatedActivity
import iss.nus.edu.sg.viewbinding.caproject.ui.claims.ClaimStatusActivity
import iss.nus.edu.sg.viewbinding.caproject.ui.main.MainActivity
import iss.nus.edu.sg.viewbinding.caproject.validation.ExpenseValidator
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AddExpenseActivity : AuthenticatedActivity() {

    private lateinit var binding: ActivityAddExpenseBinding
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var tripRequest: TripRequestData
    private var selectedDate: LocalDate? = null
    private var receiptUri: Uri? = null
    private var receiptName: String = ""
    private var cameraReceiptUri: Uri? = null

    private val receiptPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) bindSelectedReceipt(uri)
    }

    private val cameraCapture = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        if (saved) cameraReceiptUri?.let(::bindSelectedReceipt)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        binding = ActivityAddExpenseBinding.inflate(layoutInflater)
        setContentView(binding.root)
        expenseRepository = ExpenseRepository.create(this)

        val suppliedTrip = IntentCompat.getSerializableExtra(
            intent,
            TripRequestData.EXTRA_KEY,
            TripRequestData::class.java,
        ) ?: CurrentTripStore.currentTrip
        if (suppliedTrip == null) {
            finish()
            return
        }
        tripRequest = suppliedTrip
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
        binding.cameraReceiptButton.setOnClickListener { openCamera() }
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

    private fun openCamera() {
        val directory = File(cacheDir, "receipts").apply { mkdirs() }
        val file = File.createTempFile("receipt_", ".jpg", directory)
        cameraReceiptUri = FileProvider.getUriForFile(
            this,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file,
        )
        cameraCapture.launch(requireNotNull(cameraReceiptUri))
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
        val categoryLabel = binding.categoryInput.text?.toString().orEmpty().trim()
        var isValid = true

        binding.receiptError.isVisible = receiptUri == null
        binding.receiptError.setText(R.string.receipt_required)
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

        binding.categoryInputLayout.error = if (categoryLabel.toBackendCategory() == null) {
            isValid = false
            getString(R.string.category_required)
        } else null

        if (!isValid) return
        submitExpense(
            merchant = merchant,
            date = requireNotNull(selectedDate),
            amount = BigDecimal(amountText.replace(",", "")),
            category = requireNotNull(categoryLabel.toBackendCategory()),
            notes = binding.notesInput.text?.toString().orEmpty().trim(),
        )
    }

    private fun submitExpense(
        merchant: String,
        date: LocalDate,
        amount: BigDecimal,
        category: String,
        notes: String,
    ) {
        val tripId = tripRequest.remoteId
        val uri = receiptUri
        if (tripId == null || uri == null) {
            showMessage(getString(R.string.expense_trip_required))
            return
        }
        setLoading(true)
        lifecycleScope.launch {
            val receipt = try {
                readReceipt(uri)
            } catch (error: ReceiptValidationException) {
                setLoading(false)
                binding.receiptError.setText(error.messageResource)
                binding.receiptError.isVisible = true
                return@launch
            }
            val description = ExpenseDescriptionCodec.encode(merchant, date, notes)
            when (
                val result = expenseRepository.uploadReceipt(
                    tripId = tripId,
                    category = category,
                    amount = amount,
                    currency = "SGD",
                    description = description,
                    receipt = receipt,
                )
            ) {
                is ApiResult.Success -> {
                    startActivity(ClaimStatusActivity.createIntent(this@AddExpenseActivity, result.value.id))
                    finish()
                }
                is ApiResult.Failure -> {
                    setLoading(false)
                    val snackbar = Snackbar.make(
                        binding.root,
                        expenseMessageFor(result),
                        Snackbar.LENGTH_LONG,
                    )
                    if (result.isExpenseRetryable()) {
                        snackbar.setAction(R.string.retry) {
                            submitExpense(merchant, date, amount, category, notes)
                        }
                    }
                    snackbar.show()
                }
            }
        }
    }

    private suspend fun readReceipt(uri: Uri): ReceiptUpload = withContext(Dispatchers.IO) {
        val fileName = queryDisplayName(uri) ?: "receipt.jpg"
        val mimeType = contentResolver.getType(uri) ?: mimeTypeFor(fileName)
        if (mimeType !in ALLOWED_MIME_TYPES) {
            throw ReceiptValidationException(R.string.receipt_type_invalid)
        }
        val declaredSize = querySize(uri)
        if (declaredSize != null && declaredSize > MAX_RECEIPT_BYTES) {
            throw ReceiptValidationException(R.string.receipt_too_large)
        }
        val bytes = contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes()
        } ?: throw ReceiptValidationException(R.string.receipt_read_failed)
        if (bytes.isEmpty()) throw ReceiptValidationException(R.string.receipt_read_failed)
        if (bytes.size > MAX_RECEIPT_BYTES) {
            throw ReceiptValidationException(R.string.receipt_too_large)
        }
        ReceiptUpload(fileName = fileName, mimeType = mimeType, bytes = bytes)
    }

    private fun queryDisplayName(uri: Uri): String? = queryColumn(uri, OpenableColumns.DISPLAY_NAME)

    private fun querySize(uri: Uri): Long? {
        return queryColumn(uri, OpenableColumns.SIZE)?.toLongOrNull()
    }

    private fun queryColumn(uri: Uri, column: String): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(column), null, null, null)
            if (cursor?.moveToFirst() == true) {
                val index = cursor.getColumnIndex(column)
                if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
            } else null
        } finally {
            cursor?.close()
        }
    }

    private fun mimeTypeFor(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase(Locale.ENGLISH)) {
            "png" -> "image/png"
            else -> "image/jpeg"
        }
    }

    private fun String.toBackendCategory(): String? {
        return when (this) {
            getString(R.string.expense_category_flight) -> "FLIGHT"
            getString(R.string.expense_category_hotel) -> "HOTEL"
            getString(R.string.expense_category_meal) -> "MEAL"
            getString(R.string.expense_category_transport) -> "TRANSPORT"
            getString(R.string.expense_category_other) -> "OTHER"
            else -> null
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.submissionProgress.isVisible = loading
        binding.submitExpenseButton.isEnabled = !loading
        binding.receiptPickerButton.isEnabled = !loading
        binding.cameraReceiptButton.isEnabled = !loading
        binding.submitExpenseButton.setText(
            if (loading) R.string.uploading_receipt else R.string.submit_for_reimbursement,
        )
    }

    private fun showMessage(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
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

    private class ReceiptValidationException(val messageResource: Int) : Exception()

    companion object {
        private const val MAX_RECEIPT_BYTES = 10 * 1024 * 1024
        private val ALLOWED_MIME_TYPES = setOf("image/jpeg", "image/png")
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

        fun createIntent(context: Context, tripRequest: TripRequestData): Intent {
            return Intent(context, AddExpenseActivity::class.java).apply {
                putExtra(TripRequestData.EXTRA_KEY, tripRequest)
            }
        }
    }
}
