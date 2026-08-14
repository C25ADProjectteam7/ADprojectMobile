package iss.nus.edu.sg.viewbinding.caproject.data.repository

import java.time.LocalDate

data class ExpenseDescriptionMetadata(
    val merchant: String?,
    val date: LocalDate?,
    val notes: String,
)

object ExpenseDescriptionCodec {

    private const val MERCHANT_PREFIX = "Merchant: "
    private const val DATE_PREFIX = "Expense date: "
    private const val NOTES_PREFIX = "Notes: "

    fun encode(merchant: String, date: LocalDate, notes: String): String {
        val normalizedMerchant = merchant.replace(Regex("[\\r\\n]+"), " ").trim()
        val normalizedNotes = notes.replace("\r\n", "\n").trim()
        return buildList {
            add("$MERCHANT_PREFIX$normalizedMerchant")
            add("$DATE_PREFIX$date")
            if (normalizedNotes.isNotBlank()) add("$NOTES_PREFIX$normalizedNotes")
        }.joinToString("\n")
    }

    fun decode(description: String?): ExpenseDescriptionMetadata {
        val raw = description.orEmpty()
        val lines = raw.lines()
        val merchant = lines.firstOrNull { it.startsWith(MERCHANT_PREFIX) }
            ?.removePrefix(MERCHANT_PREFIX)
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val date = lines.firstOrNull { it.startsWith(DATE_PREFIX) }
            ?.removePrefix(DATE_PREFIX)
            ?.trim()
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val notesLine = lines.indexOfFirst { it.startsWith(NOTES_PREFIX) }
        val notes = when {
            notesLine >= 0 -> lines.drop(notesLine).joinToString("\n")
                .removePrefix(NOTES_PREFIX)
                .trim()
            merchant == null && date == null -> raw.trim()
            else -> ""
        }
        return ExpenseDescriptionMetadata(merchant = merchant, date = date, notes = notes)
    }
}
