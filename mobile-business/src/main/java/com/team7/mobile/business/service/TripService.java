package com.team7.mobile.business.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team7.mobile.common.dto.ItineraryDTO;
import com.team7.mobile.common.dto.ItineraryItemDTO;
import com.team7.mobile.common.dto.TripDTO;
import com.team7.mobile.common.dto.TripDetailDTO;
import com.team7.mobile.common.dto.TripRequest;
import com.team7.mobile.data.entity.Itinerary;
import com.team7.mobile.data.entity.ItineraryItem;
import com.team7.mobile.data.entity.Trip;
import com.team7.mobile.data.entity.User;
import com.team7.mobile.data.repository.ItineraryItemRepository;
import com.team7.mobile.data.repository.ItineraryRepository;
import com.team7.mobile.data.repository.TripRepository;
import com.team7.mobile.business.util.CurrentUser;
import com.team7.mobile.business.agent.AgentOrchestrator;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
    private final ItineraryRepository itineraryRepository;
    private final ItineraryItemRepository itineraryItemRepository;
    private final CurrentUser currentUser;
    private final AgentOrchestrator agentOrchestrator;
    private final ObjectMapper objectMapper;

    public TripService(TripRepository tripRepository,
                       ItineraryRepository itineraryRepository,
                       ItineraryItemRepository itineraryItemRepository,
                       CurrentUser currentUser,
                       AgentOrchestrator agentOrchestrator,
                       ObjectMapper objectMapper) {
        this.tripRepository = tripRepository;
        this.itineraryRepository = itineraryRepository;
        this.itineraryItemRepository = itineraryItemRepository;
        this.currentUser = currentUser;
        this.agentOrchestrator = agentOrchestrator;
        this.objectMapper = objectMapper;
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
     * Get full trip detail including day-by-day itinerary (owner only).
     */
    public TripDetailDTO getTripDetail(Long tripId) {
        Trip trip = findOwnedTrip(tripId);
        List<ItineraryDTO> itineraries = itineraryRepository.findByTripIdOrderByDayNumber(tripId)
                .stream().map(this::toItineraryDTO)
                .collect(Collectors.toList());
        return new TripDetailDTO(
                trip.getId(), trip.getTitle(), trip.getDestination(),
                trip.getStartDate(), trip.getEndDate(), trip.getBudgetTotal(),
                trip.getStatus().name(), itineraries
        );
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

        // Step 3: persist extracted info + generated itinerary
        Trip trip = findOwnedTrip(tripId);
        if (extracted.get("destination") != null) trip.setDestination((String) extracted.get("destination"));
        if (extracted.get("startDate") != null) trip.setStartDate(LocalDate.parse((String) extracted.get("startDate")));
        if (extracted.get("endDate") != null) trip.setEndDate(LocalDate.parse((String) extracted.get("endDate")));
        if (extracted.get("budgetTotal") != null) trip.setBudgetTotal(new BigDecimal(extracted.get("budgetTotal").toString()));
        trip.setStatus(Trip.TripStatus.PLANNED);
        tripRepository.save(trip);

        saveItinerary(trip, itinerary);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ITINERARY_READY");
        result.put("itinerary", itinerary);
        return result;
    }

    /**
     * Parse Agent itinerary JSON ({day1: {date, flight, hotel, breakfast, lunch, dinner, attraction}, ...})
     * and persist as Itinerary + ItineraryItem rows. Replaces any previous plan.
     */
    @SuppressWarnings("unchecked")
    private void saveItinerary(Trip trip, Map<String, Object> agentItinerary) {
        // Replace previous plan
        itineraryRepository.deleteByTripId(trip.getId());

        int dayNumber = 1;
        while (true) {
            Object dayObj = agentItinerary.get("day" + dayNumber);
            if (!(dayObj instanceof Map)) break;

            Map<String, Object> day = (Map<String, Object>) dayObj;
            Itinerary itinerary = new Itinerary();
            itinerary.setTrip(trip);
            itinerary.setDayNumber(dayNumber);
            itinerary.setDate(LocalDate.parse((String) day.getOrDefault("date", trip.getStartDate().toString())));
            itinerary.setGeneratedByAgent(true);
            itinerary = itineraryRepository.save(itinerary);

            saveItem(itinerary, "FLIGHT", day.get("flight"));
            saveItem(itinerary, "HOTEL", day.get("hotel"));
            saveItem(itinerary, "MEAL", day.get("breakfast"));
            saveItem(itinerary, "MEAL", day.get("lunch"));
            saveItem(itinerary, "MEAL", day.get("dinner"));
            saveItem(itinerary, "ATTRACTION", day.get("attraction"));

            dayNumber++;
        }
    }

    /** Persist one activity object (or skip if null) — keeps raw JSON in description. */
    @SuppressWarnings("unchecked")
    private void saveItem(Itinerary itinerary, String type, Object activityObj) {
        if (!(activityObj instanceof Map)) return;
        Map<String, Object> activity = (Map<String, Object>) activityObj;

        ItineraryItem item = new ItineraryItem();
        item.setItinerary(itinerary);
        item.setType(ItineraryItem.ItemType.valueOf(type));
        item.setTitle(extractTitle(activity));
        item.setStartTime(parseDateTime(activity.get("startTime")));
        item.setEndTime(parseDateTime(activity.get("endTime")));
        item.setLocation(str(activity.get("location")));
        item.setBookingRef(str(activity.get("bookingRef")));
        item.setPrice(parsePrice(activity));
        item.setCurrency(str(activity.getOrDefault("currency", "CNY")));
        // Keep the full raw activity JSON so no Agent data is lost
        try {
            item.setDescription(objectMapper.writeValueAsString(activity));
        } catch (Exception e) {
            item.setDescription(String.valueOf(activity));
        }
        itineraryItemRepository.save(item);
    }

    /** Pick a display title from common fields. */
    private String extractTitle(Map<String, Object> activity) {
        for (String key : new String[]{"title", "name", "hotelName", "airline", "restaurantName", "attractionName", "flightNumber"}) {
            if (activity.get(key) != null) return String.valueOf(activity.get(key));
        }
        return "";
    }

    private String str(Object v) { return v != null ? String.valueOf(v) : null; }

    private LocalDateTime parseDateTime(Object v) {
        if (v == null) return null;
        try { return LocalDateTime.parse(String.valueOf(v)); } catch (Exception e) { return null; }
    }

    private BigDecimal parsePrice(Map<String, Object> activity) {
        for (String key : new String[]{"price", "totalPrice", "amount"}) {
            Object v = activity.get(key);
            if (v != null) {
                try { return new BigDecimal(v.toString()); } catch (Exception e) { /* ignore */ }
            }
        }
        return null;
    }

    private ItineraryDTO toItineraryDTO(Itinerary itinerary) {
        List<ItineraryItemDTO> items = itineraryItemRepository.findByItineraryId(itinerary.getId())
                .stream().map(this::toItemDTO)
                .collect(Collectors.toList());
        return new ItineraryDTO(itinerary.getId(), itinerary.getDayNumber(), itinerary.getDate(),
                itinerary.getGeneratedByAgent(), items);
    }

    private ItineraryItemDTO toItemDTO(ItineraryItem item) {
        return new ItineraryItemDTO(
                item.getId(), item.getType().name(),
                item.getStartTime(), item.getEndTime(),
                item.getTitle(), item.getDescription(), item.getLocation(),
                item.getBookingRef(), item.getPrice(), item.getCurrency()
        );
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
                trip.getStatus().name(),
                trip.getCreatedAt()
        );
    }

    /**
     * All trips across the company (approval sync / budget review).
     * Requires MANAGER / FINANCE / ADMIN role.
     */
    public List<TripDTO> getAllTrips() {
        requireApprovalRole();
        return tripRepository.findAll().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * Throws if the current principal is not an approver (MANAGER/FINANCE/ADMIN).
     * Stateless: reads the role from the JWT authorities in the SecurityContext,
     * NOT from the DB — so tokens issued by the Web group (whose users are not
     * in our user table) still work for cross-system approval calls.
     */
    private void requireApprovalRole() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new IllegalStateException("User not authenticated");
        }
        boolean allowed = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_MANAGER")
                        || a.getAuthority().equals("ROLE_FINANCE")
                        || a.getAuthority().equals("ROLE_ADMIN"));
        if (!allowed) {
            throw new com.team7.mobile.common.exception.ForbiddenException(
                    "Only MANAGER/FINANCE/ADMIN can access company-wide data");
        }
    }
}
