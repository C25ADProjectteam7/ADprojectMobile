package com.team7.mobile.business.service;

import com.team7.mobile.common.dto.TripDTO;
import com.team7.mobile.common.dto.TripRequest;
import com.team7.mobile.data.entity.Trip;
import com.team7.mobile.data.entity.User;
import com.team7.mobile.data.repository.TripRepository;
import com.team7.mobile.business.util.CurrentUser;
import com.team7.mobile.business.agent.AgentOrchestrator;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Trip management — create, query, update, cancel trips, agent conversation.
 */
@Service
public class TripService {

    private final TripRepository tripRepository;
    private final CurrentUser currentUser;
    private final AgentOrchestrator agentOrchestrator;

    public TripService(TripRepository tripRepository, CurrentUser currentUser,
                       AgentOrchestrator agentOrchestrator) {
        this.tripRepository = tripRepository;
        this.currentUser = currentUser;
        this.agentOrchestrator = agentOrchestrator;
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

    /**
     * Agent conversation for a trip.
     * Flow: extract requirements → if missing fields, ask clarifying question;
     * otherwise generate itinerary and return it.
     * Backlog #4 + #6.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> agentChat(Long tripId, String message) {
        findOwnedTrip(tripId);  // auth check

        // Step 1: extract structured trip requirements from free text
        Map<String, Object> extracted = agentOrchestrator.extractRequirements(message);

        List<String> missing = (List<String>) extracted.get("missingFields");
        if (missing != null && !missing.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "NEEDS_MORE_INFO");
            result.put("missingFields", missing);
            result.put("clarifyingQuestion", extracted.get("clarifyingQuestion"));
            return result;
        }

        // Step 2: generate full itinerary
        Map<String, Object> tripData = new LinkedHashMap<>();
        tripData.put("originCity", extracted.get("originCity"));
        tripData.put("destination", extracted.get("destination"));
        tripData.put("startDate", extracted.get("startDate"));
        tripData.put("endDate", extracted.get("endDate"));
        tripData.put("budgetTotal", extracted.get("budgetTotal"));
        Object prefs = extracted.get("preferences");
        tripData.put("preferences", prefs != null ? prefs : List.of());

        Map<String, Object> itinerary = agentOrchestrator.generateItinerary(tripData);

        // Step 3: persist extracted info back to the trip record
        Trip trip = findOwnedTrip(tripId);
        if (extracted.get("destination") != null) trip.setDestination((String) extracted.get("destination"));
        if (extracted.get("startDate") != null) trip.setStartDate(LocalDate.parse((String) extracted.get("startDate")));
        if (extracted.get("endDate") != null) trip.setEndDate(LocalDate.parse((String) extracted.get("endDate")));
        if (extracted.get("budgetTotal") != null) trip.setBudgetTotal(new BigDecimal(extracted.get("budgetTotal").toString()));
        trip.setStatus(Trip.TripStatus.PLANNED);
        tripRepository.save(trip);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ITINERARY_READY");
        result.put("itinerary", itinerary);
        return result;
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
