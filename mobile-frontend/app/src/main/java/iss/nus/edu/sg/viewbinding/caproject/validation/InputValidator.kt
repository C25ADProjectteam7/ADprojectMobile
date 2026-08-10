package iss.nus.edu.sg.viewbinding.caproject.validation

import java.time.LocalDate

object InputValidator {

    private val emailPattern = Regex(
        "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$",
    )

    fun isValidEmail(value: String): Boolean = emailPattern.matches(value.trim())

    fun isValidOptionalEmail(value: String): Boolean {
        return value.isBlank() || isValidEmail(value)
    }

    fun isValidRegistrationUsername(value: String): Boolean {
        return value.trim().length in 3..50
    }

    fun isValidRegistrationPassword(value: String): Boolean {
        return value.length in 8..100
    }

    fun isPositiveBudget(value: String): Boolean {
        val normalizedValue = value.replace(",", "").trim()
        return normalizedValue.toDoubleOrNull()?.let { it > 0 } == true
    }

    fun isValidDateRange(startDate: LocalDate?, endDate: LocalDate?): Boolean {
        return startDate != null && endDate != null && !endDate.isBefore(startDate)
    }
}
