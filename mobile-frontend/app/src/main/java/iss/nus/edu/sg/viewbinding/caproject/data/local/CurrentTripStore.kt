package iss.nus.edu.sg.viewbinding.caproject.data.local

import iss.nus.edu.sg.viewbinding.caproject.model.TripRequestData

object CurrentTripStore {

    var currentTrip: TripRequestData? = null
        private set

    fun saveRequest(tripRequest: TripRequestData) {
        currentTrip = tripRequest
    }
}
