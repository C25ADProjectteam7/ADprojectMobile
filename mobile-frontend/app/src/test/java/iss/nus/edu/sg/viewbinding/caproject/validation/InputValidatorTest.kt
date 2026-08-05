package iss.nus.edu.sg.viewbinding.caproject.validation

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputValidatorTest {

    @Test
    fun validWorkEmail_isAccepted() {
        assertTrue(InputValidator.isValidEmail("ashley.tan@company.com.sg"))
    }

    @Test
    fun malformedEmail_isRejected() {
        assertFalse(InputValidator.isValidEmail("ashley.tan@company"))
    }

    @Test
    fun positiveBudget_withComma_isAccepted() {
        assertTrue(InputValidator.isPositiveBudget("2,000"))
    }

    @Test
    fun zeroBudget_isRejected() {
        assertFalse(InputValidator.isPositiveBudget("0"))
    }

    @Test
    fun endDateBeforeStartDate_isRejected() {
        val startDate = LocalDate.of(2026, 8, 14)
        val endDate = LocalDate.of(2026, 8, 12)

        assertFalse(InputValidator.isValidDateRange(startDate, endDate))
    }
}
