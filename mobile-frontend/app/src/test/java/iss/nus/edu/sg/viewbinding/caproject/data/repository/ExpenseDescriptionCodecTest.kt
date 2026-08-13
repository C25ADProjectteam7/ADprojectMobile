package iss.nus.edu.sg.viewbinding.caproject.data.repository

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class ExpenseDescriptionCodecTest {

    @Test
    fun encodeAndDecodePreserveManualReceiptFields() {
        val description = ExpenseDescriptionCodec.encode(
            merchant = "Blue Jasmine\nRooftop",
            date = LocalDate.of(2026, 8, 13),
            notes = "Client dinner\nFour attendees",
        )

        assertEquals(
            "Merchant: Blue Jasmine Rooftop\n" +
                "Expense date: 2026-08-13\n" +
                "Notes: Client dinner\nFour attendees",
            description,
        )
        val decoded = ExpenseDescriptionCodec.decode(description)
        assertEquals("Blue Jasmine Rooftop", decoded.merchant)
        assertEquals(LocalDate.of(2026, 8, 13), decoded.date)
        assertEquals("Client dinner\nFour attendees", decoded.notes)
    }

    @Test
    fun decodeKeepsLegacyBackendDescriptionAsNotes() {
        val decoded = ExpenseDescriptionCodec.decode("Taxi from airport")

        assertEquals(null, decoded.merchant)
        assertEquals(null, decoded.date)
        assertEquals("Taxi from airport", decoded.notes)
    }
}
