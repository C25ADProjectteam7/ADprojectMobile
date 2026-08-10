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
    fun blankOptionalEmail_isAccepted() {
        assertTrue(InputValidator.isValidOptionalEmail(""))
    }

    @Test
    fun registrationUsername_respectsBackendLength() {
        assertTrue(InputValidator.isValidRegistrationUsername("ashley.tan"))
        assertFalse(InputValidator.isValidRegistrationUsername("ab"))
        assertFalse(InputValidator.isValidRegistrationUsername("a".repeat(51)))
    }

    @Test
    fun registrationPassword_respectsBackendLength() {
        assertTrue(InputValidator.isValidRegistrationPassword("travel123"))
        assertFalse(InputValidator.isValidRegistrationPassword("short7"))
        assertFalse(InputValidator.isValidRegistrationPassword("a".repeat(101)))
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
