package iss.nus.edu.sg.viewbinding.caproject.data.repository

import com.google.gson.JsonParser
import iss.nus.edu.sg.viewbinding.caproject.model.DailyItinerary
import iss.nus.edu.sg.viewbinding.caproject.model.ItineraryItem
import iss.nus.edu.sg.viewbinding.caproject.model.ItineraryItemState
import iss.nus.edu.sg.viewbinding.caproject.model.TripDetailData
import iss.nus.edu.sg.viewbinding.caproject.model.TripRequestData
import iss.nus.edu.sg.viewbinding.caproject.network.model.trip.ItineraryItemResponse
import iss.nus.edu.sg.viewbinding.caproject.network.model.trip.TripDetailResponse
import iss.nus.edu.sg.viewbinding.caproject.network.model.trip.TripResponse
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun TripResponse.toTripRequestData(
    preferences: ArrayList<String> = arrayListOf(),
    notes: String = "",
): TripRequestData {
    return TripRequestData(
        destination = destination,
        startDate = LocalDate.parse(startDate),
        endDate = LocalDate.parse(endDate),
        budget = budgetTotal.toDouble(),
        preferences = preferences,
        notes = notes,
        remoteId = id,
        remoteTitle = title,
        remoteStatus = status,
    )
}

internal fun TripDetailResponse.toTripDetailData(
    preferences: ArrayList<String> = arrayListOf(),
    notes: String = "",
): TripDetailData {
    val trip = TripRequestData(
        destination = destination,
        startDate = LocalDate.parse(startDate),
        endDate = LocalDate.parse(endDate),
        budget = budgetTotal.toDouble(),
        preferences = preferences,
        notes = notes,
        remoteId = id,
        remoteTitle = title,
        remoteStatus = status,
    )
    val days = itineraries
        .sortedBy { it.dayNumber }
        .map { itinerary ->
            DailyItinerary(
                dayNumber = itinerary.dayNumber,
                date = LocalDate.parse(itinerary.date),
                route = itinerary.items.firstNotNullOfOrNull { it.location?.takeIf(String::isNotBlank) }
                    ?: "Daily plan in ${trip.city}",
                items = itinerary.items.map(ItineraryItemResponse::toItineraryItem),
            )
        }
    return TripDetailData(trip = trip, days = days)
}

internal fun TripRequestData.toNetworkRequest(): iss.nus.edu.sg.viewbinding.caproject.network.model.trip.TripRequest {
    return iss.nus.edu.sg.viewbinding.caproject.network.model.trip.TripRequest(
        title = displayTitle,
        destination = destination,
        startDate = startDate.toString(),
        endDate = endDate.toString(),
        budgetTotal = BigDecimal.valueOf(budget),
        preferences = preferences,
    )
}

private fun ItineraryItemResponse.toItineraryItem(): ItineraryItem {
    val detailParts = listOfNotNull(
        description.toReadableAgentDescription(),
        location?.takeIf(String::isNotBlank),
        bookingRef?.takeIf(String::isNotBlank)?.let { "Booking $it" },
        price?.let { value -> "${currency.orEmpty().ifBlank { "SGD" }} ${value.stripTrailingZeros().toPlainString()}" },
    )
    return ItineraryItem(
        time = startTime.toDisplayTime(),
        title = title,
        detail = detailParts.joinToString(" · ").ifBlank {
            type.replace('_', ' ')
                .lowercase(Locale.ENGLISH)
                .replaceFirstChar { it.titlecase(Locale.ENGLISH) }
        },
        state = if (bookingRef.isNullOrBlank()) ItineraryItemState.PLANNED else ItineraryItemState.CONFIRMED,
        type = type,
        endTime = endTime.toDisplayTime().takeUnless { it == "Any time" },
        price = price,
        currency = currency,
        location = location,
        bookingRef = bookingRef,
    )
}

private fun String?.toReadableAgentDescription(): String? {
    val value = this?.trim()?.takeIf(String::isNotBlank) ?: return null
    if (!value.startsWith('{')) return value
    val json = runCatching { JsonParser.parseString(value).asJsonObject }.getOrNull() ?: return value
    val preferredKeys = listOf("description", "summary", "details", "address", "terminal", "flightNumber")
    val preferredValues = preferredKeys.mapNotNull { key ->
        json.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank)
    }.distinct()
    if (preferredValues.isNotEmpty()) return preferredValues.joinToString(" · ")

    val hiddenKeys = setOf(
        "title", "name", "hotelName", "airline", "restaurantName", "attractionName",
        "startTime", "endTime", "location", "bookingRef", "price", "totalPrice", "amount", "currency",
    )
    return json.entrySet()
        .filter { (key, element) -> key !in hiddenKeys && element.isJsonPrimitive }
        .mapNotNull { (_, element) -> element.asString.takeIf(String::isNotBlank) }
        .distinct()
        .joinToString(" · ")
        .takeIf(String::isNotBlank)
}

private fun String?.toDisplayTime(): String {
    if (this.isNullOrBlank()) return "Any time"
    val time = runCatching { OffsetDateTime.parse(this).toLocalTime() }
        .recoverCatching { LocalDateTime.parse(this).toLocalTime() }
        .getOrElse { throw IllegalArgumentException("Invalid itinerary time") }
    return time.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH))
}
