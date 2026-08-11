package iss.nus.edu.sg.viewbinding.caproject.model

data class TripDetailData(
    val trip: TripRequestData,
    val days: List<DailyItinerary>,
)
