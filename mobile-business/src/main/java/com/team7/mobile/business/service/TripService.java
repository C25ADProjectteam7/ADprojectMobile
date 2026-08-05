package com.team7.mobile.business.service;

import com.team7.mobile.common.dto.TripDTO;
import com.team7.mobile.common.dto.TripRequest;
import com.team7.mobile.data.entity.Trip;
import com.team7.mobile.data.entity.User;
import com.team7.mobile.data.repository.TripRepository;
import com.team7.mobile.business.util.CurrentUser;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Trip management — create, query, update, cancel trips.
 */
@Service
public class TripService {

    private final TripRepository tripRepository;
    private final CurrentUser currentUser;

    public TripService(TripRepository tripRepository, CurrentUser currentUser) {
        this.tripRepository = tripRepository;
        this.currentUser = currentUser;
    }

    /**
     * Create a new trip (manual creation, without Agent planning).
     */
    public TripDTO createTrip(TripRequest request) {
        User user = currentUser.get();
        if (user == null) {
            throw new IllegalStateException("User not authenticated");
        }
        if (request.getEndDate().isBefore(request.getStartDate())) {
            throw new IllegalArgumentException("endDate must be after startDate");
        }

        Trip trip = new Trip();
        trip.setUser(user);
        trip.setTitle(request.getTitle());
        trip.setDestination(request.getDestination());
        trip.setStartDate(request.getStartDate());
        trip.setEndDate(request.getEndDate());
        trip.setBudgetTotal(request.getBudgetTotal());
        trip.setStatus(Trip.TripStatus.DRAFT);

        trip = tripRepository.save(trip);
        return toDTO(trip);
    }

    /**
     * Get a single trip by id (owner only).
     */
    public TripDTO getTripById(Long tripId) {
        Trip trip = findOwnedTrip(tripId);
        return toDTO(trip);
    }

    /**
     * List all trips of the current user, newest first.
     */
    public List<TripDTO> getUserTrips() {
        Long userId = currentUser.getId();
        return tripRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * Update trip details (owner only).
     */
    public TripDTO updateTrip(Long tripId, TripRequest request) {
        Trip trip = findOwnedTrip(tripId);
        if (request.getTitle() != null) trip.setTitle(request.getTitle());
        if (request.getDestination() != null) trip.setDestination(request.getDestination());
        if (request.getStartDate() != null) trip.setStartDate(request.getStartDate());
        if (request.getEndDate() != null) trip.setEndDate(request.getEndDate());
        if (request.getBudgetTotal() != null) trip.setBudgetTotal(request.getBudgetTotal());
        trip = tripRepository.save(trip);
        return toDTO(trip);
    }

    /**
     * Cancel a trip (owner only). Sets status to CANCELLED.
     */
    public void cancelTrip(Long tripId) {
        Trip trip = findOwnedTrip(tripId);
        trip.setStatus(Trip.TripStatus.CANCELLED);
        tripRepository.save(trip);
    }

    private Trip findOwnedTrip(Long tripId) {
        Long userId = currentUser.getId();
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("Trip not found: " + tripId));
        if (!trip.getUser().getId().equals(userId)) {
            throw new RuntimeException("Not authorized to access trip: " + tripId);
        }
        return trip;
    }

    private TripDTO toDTO(Trip trip) {
        return new TripDTO(
                trip.getId(),
                trip.getUser().getId(),
                trip.getTitle(),
                trip.getDestination(),
                trip.getStartDate(),
                trip.getEndDate(),
                trip.getBudgetTotal(),
                trip.getStatus().name()
        );
    }
}
