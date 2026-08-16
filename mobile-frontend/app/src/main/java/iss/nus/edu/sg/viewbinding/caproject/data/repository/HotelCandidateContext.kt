package iss.nus.edu.sg.viewbinding.caproject.data.repository

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import iss.nus.edu.sg.viewbinding.caproject.model.HotelCandidate
import iss.nus.edu.sg.viewbinding.caproject.model.ItineraryItem

/**
 * Reads the hotel identity and this trip's candidate hotels out of the Agent's
 * activity JSON.
 *
 * Both fields are written programmatically by the Agent (never by the assembly
 * LLM) and are carried verbatim through Spring's itinerary-item description, so
 * they are parsed as STRUCTURED JSON here. `ItineraryItem.detail` is a lossy
 * human-readable flattening of the same object and is never parsed back apart.
 *
 * Everything is best-effort: an older itinerary generated before candidate
 * context existed simply yields null / an empty list, and the caller falls back
 * to the existing price-advice flow.
 */
object HotelCandidateContext {

    private fun objectOf(item: ItineraryItem): JsonObject? {
        val raw = item.rawJson?.trim()?.takeIf { it.startsWith("{") } ?: return null
        return runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf(String::isNotEmpty)

    /** LiteAPI hotel id for this stay, or null for a pre-candidate itinerary. */
    fun hotelId(item: ItineraryItem): String? = objectOf(item)?.stringOrNull("hotelId")

    /**
     * The hotels the Agent offered for this trip, in its own ranked order.
     *
     * IDENTITY ONLY. The Agent's stayTotalPrice/averagePricePerNight are
     * deliberately not read: they are whole-stay USD booking quotes, while the
     * ML service re-probes every hotel on its own one-night INR contract. The
     * two are different measurements and must not be mixed.
     */
    fun candidates(item: ItineraryItem): List<HotelCandidate> {
        val array = objectOf(item)?.get("candidateHotels")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = obj.stringOrNull("hotelId") ?: return@mapNotNull null
            HotelCandidate(hotelId = id, hotelName = obj.stringOrNull("hotelName"))
        }
    }
}
