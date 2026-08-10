package iss.nus.edu.sg.viewbinding.caproject.data.mock

import iss.nus.edu.sg.viewbinding.caproject.model.ClaimStatus
import iss.nus.edu.sg.viewbinding.caproject.model.PolicyResult
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CurrentExpenseStoreTest {

    @After
    fun tearDown() {
        CurrentExpenseStore.clear()
    }

    @Test
    fun submittedExpense_isStoredAndProducesUnderReviewClaim() {
        val submittedAt = LocalDateTime.of(2026, 8, 14, 17, 42)
        val expense = CurrentExpenseStore.submit(
            destination = "London",
            merchant = "Blue Jasmine Rooftop",
            date = LocalDate.of(2026, 8, 13),
            amount = 68.50,
            category = "Meals & entertainment",
            notes = "Client dinner",
            receiptUri = "content://receipt/1308",
            receiptName = "receipt_1308.jpg",
            submittedAt = submittedAt,
        )

        val claim = MockExpenseData.claimFor(expense)

        assertNotNull(CurrentExpenseStore.latestExpense)
        assertEquals("London", claim.destination)
        assertEquals("Blue Jasmine Rooftop", claim.merchant)
        assertEquals("receipt_1308.jpg", claim.receiptName)
        assertEquals(68.50, claim.amount, 0.0)
        assertEquals(PolicyResult.WITHIN_POLICY, claim.policyResult)
        assertEquals(ClaimStatus.UNDER_REVIEW, claim.status)
        assertEquals("CLM-2026-0813", claim.reference)
    }

    @Test
    fun expenseAboveCategoryLimit_requiresPolicyReview() {
        val expense = CurrentExpenseStore.submit(
            destination = "London",
            merchant = "Restaurant",
            date = LocalDate.of(2026, 8, 13),
            amount = 120.0,
            category = "Meals & entertainment",
            notes = "",
            receiptUri = "content://receipt/high",
            receiptName = "high.jpg",
        )

        assertEquals(PolicyResult.REVIEW_REQUIRED, expense.policyResult)
    }
}
