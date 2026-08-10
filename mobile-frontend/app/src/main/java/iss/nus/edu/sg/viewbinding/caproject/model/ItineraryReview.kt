package iss.nus.edu.sg.viewbinding.caproject.model

data class ItineraryReview(
    val flightRoute: String,
    val flightSummary: String,
    val flightSchedule: String,
    val flightNumber: String,
    val flightPrice: Double,
    val hotelName: String,
    val hotelSummary: String,
    val hotelPrediction: String,
    val hotelStay: String,
    val hotelRate: String,
    val hotelPrice: Double,
    val transferTitle: String,
    val transferDescription: String,
    val transferDuration: String,
    val estimatedTotal: Double,
)
