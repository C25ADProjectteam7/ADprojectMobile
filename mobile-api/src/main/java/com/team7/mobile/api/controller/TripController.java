package com.team7.mobile.api.controller;

import com.team7.mobile.common.dto.TripDTO;
import com.team7.mobile.common.dto.TripDetailDTO;
import com.team7.mobile.common.dto.TripRequest;
import com.team7.mobile.business.service.AgentChatService;
import com.team7.mobile.business.service.TripService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/trips")
public class TripController {

    private final TripService tripService;
    private final AgentChatService agentChatService;

    public TripController(TripService tripService, AgentChatService agentChatService) {
        this.tripService = tripService;
        this.agentChatService = agentChatService;
    }

    /** Create a new trip */
    @PostMapping
    public ResponseEntity<TripDTO> createTrip(@Valid @RequestBody TripRequest request) {
        return ResponseEntity.ok(tripService.createTrip(request));
    }

    /** List current user's trips */
    @GetMapping
    public ResponseEntity<List<TripDTO>> getUserTrips() {
        return ResponseEntity.ok(tripService.getUserTrips());
    }

    /** Get trip basic info */
    @GetMapping("/{id}")
    public ResponseEntity<TripDTO> getTrip(@PathVariable Long id) {
        return ResponseEntity.ok(tripService.getTripById(id));
    }

    /** Get trip detail incl. full day-by-day itinerary */
    @GetMapping("/{id}/detail")
    public ResponseEntity<TripDetailDTO> getTripDetail(@PathVariable Long id) {
        return ResponseEntity.ok(tripService.getTripDetail(id));
    }

    /** Update trip */
    @PutMapping("/{id}")
    public ResponseEntity<TripDTO> updateTrip(@PathVariable Long id,
                                              @Valid @RequestBody TripRequest request) {
        return ResponseEntity.ok(tripService.updateTrip(id, request));
    }

    /** Cancel trip */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> cancelTrip(@PathVariable Long id) {
        tripService.cancelTrip(id);
        return ResponseEntity.ok(Map.of("message", "Trip cancelled"));
    }

    /**
     * Chat with the Agent to plan/modify this trip — async.
     * Body: { "message": "I want to go to Singapore for 3 days, budget 2000" }
     * Returns immediately: { "taskId": "...", "status": "PROCESSING" }
     * Poll GET /api/agent/tasks/{taskId} for the result.
     */
    @PostMapping("/{id}/agent-chat")
    public ResponseEntity<Map<String, Object>> agentChat(@PathVariable Long id,
                                                         @RequestBody Map<String, String> body) {
        String message = body.get("message");
        String taskId = agentChatService.startTask(id, message);
        return ResponseEntity.accepted().body(Map.of(
                "taskId", taskId,
                "status", "PROCESSING"
        ));
    }

    /**
     * Modify this trip's existing itinerary via a natural-language change
     * request — async, same contract as agent-chat.
     * Body: { "message": "can you find me a cheaper hotel instead?" }
     * Returns immediately: { "taskId": "...", "status": "PROCESSING" }
     * Poll GET /api/agent/tasks/{taskId} for the result.
     * Requires an itinerary to already exist for this trip (via agent-chat).
     */
    @PostMapping("/{id}/agent-modify")
    public ResponseEntity<Map<String, Object>> agentModify(@PathVariable Long id,
                                                           @RequestBody Map<String, String> body) {
        String message = body.get("message");
        String taskId = agentChatService.startModifyTask(id, message);
        return ResponseEntity.accepted().body(Map.of(
                "taskId", taskId,
                "status", "PROCESSING"
        ));
    }

    /**
     * Books flight + hotel for this trip via the Agent (synchronous - see
     * TripService.bookTrip() for why) and persists whichever leg(s)
     * succeeded to the bookings table.
     * Body must match agent-ml-service's BookTripRequest (itinerary,
     * flightOfferId, hotelOfferId, passengerName, passengerDob, email, plus
     * optional origin/destination/date/latitude/longitude/checkIn/checkOut/
     * budget/guestNationality for stale-offer recovery).
     */
    @PostMapping("/{id}/book-trip")
    public ResponseEntity<Map<String, Object>> bookTrip(@PathVariable Long id,
                                                        @RequestBody Map<String, Object> request) {
        return ResponseEntity.ok(tripService.bookTrip(id, request));
    }
}
