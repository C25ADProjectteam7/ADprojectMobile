package iss.nus.edu.sg.viewbinding.caproject.validation

import org.junit.Assert.assertEquals
import org.junit.Test

class BudgetCalculatorTest {

    @Test
    fun estimateWithinBudget_returnsExpectedPercentage() {
        assertEquals(35, BudgetCalculator.progressPercent(700.0, 2_000.0))
    }

    @Test
    fun estimateAboveBudget_isCappedAtOneHundred() {
        assertEquals(100, BudgetCalculator.progressPercent(700.0, 500.0))
    }

    @Test
    fun invalidBudget_returnsZero() {
        assertEquals(0, BudgetCalculator.progressPercent(700.0, 0.0))
    }
}
