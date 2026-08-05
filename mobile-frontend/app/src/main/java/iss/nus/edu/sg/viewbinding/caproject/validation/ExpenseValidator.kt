package iss.nus.edu.sg.viewbinding.caproject.validation

import java.time.LocalDate

object ExpenseValidator {

    fun isPositiveAmount(value: String): Boolean {
        return value.replace(",", "").toDoubleOrNull()?.let { it > 0.0 } == true
    }

    fun isDateWithinTrip(date: LocalDate?, startDate: LocalDate, endDate: LocalDate): Boolean {
        return date != null && !date.isBefore(startDate) && !date.isAfter(endDate)
    }
}
