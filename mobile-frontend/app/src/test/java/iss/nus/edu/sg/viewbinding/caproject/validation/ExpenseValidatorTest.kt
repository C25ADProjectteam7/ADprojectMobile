package iss.nus.edu.sg.viewbinding.caproject.validation

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpenseValidatorTest {

    @Test
    fun positiveDecimalAmount_isAccepted() {
        assertTrue(ExpenseValidator.isPositiveAmount("68.50"))
    }

    @Test
    fun zeroAndMalformedAmounts_areRejected() {
        assertFalse(ExpenseValidator.isPositiveAmount("0"))
        assertFalse(ExpenseValidator.isPositiveAmount("sixty"))
    }

    @Test
    fun expenseDate_mustBeWithinTrip() {
        val start = LocalDate.of(2026, 8, 12)
        val end = LocalDate.of(2026, 8, 14)

        assertTrue(ExpenseValidator.isDateWithinTrip(LocalDate.of(2026, 8, 13), start, end))
        assertFalse(ExpenseValidator.isDateWithinTrip(LocalDate.of(2026, 8, 15), start, end))
        assertFalse(ExpenseValidator.isDateWithinTrip(null, start, end))
    }
}
