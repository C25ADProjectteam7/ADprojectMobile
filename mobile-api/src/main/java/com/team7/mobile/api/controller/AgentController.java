package com.team7.mobile.api.controller;

import com.team7.mobile.business.agent.AgentOrchestrator;
import com.team7.mobile.business.service.AgentChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentOrchestrator agentOrchestrator;
    private final AgentChatService agentChatService;

    public AgentController(AgentOrchestrator agentOrchestrator, AgentChatService agentChatService) {
        this.agentOrchestrator = agentOrchestrator;
        this.agentChatService = agentChatService;
    }

    /**
     * Poll the result of an async agent task (started via POST /api/trips/{id}/agent-chat).
     * Returns: { status: PROCESSING } while running,
     *          { status: DONE, result: {...} } when finished,
     *          { status: FAILED, error: "..." } on failure.
     */
    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<Map<String, Object>> getTask(@PathVariable String taskId) {
        return ResponseEntity.ok(agentChatService.getTask(taskId));
    }

    /**
     * Backlog #4: Parse free-text trip requirements.
     * Frontend sends raw user input → Agent returns structured fields or clarifying questions.
     */
    @PostMapping("/extract-requirements")
    public ResponseEntity<Map<String, Object>> extractRequirements(@RequestBody Map<String, String> request) {
        String userInput = request.get("userInput");
        return ResponseEntity.ok(agentOrchestrator.extractRequirements(userInput));
    }

    /**
     * Backlog #6: Generate full day-by-day itinerary.
     * Frontend sends structured trip data (origin, destination, dates, budget, preferences).
     */
    @PostMapping("/generate-itinerary")
    public ResponseEntity<Map<String, Object>> generateItinerary(@RequestBody Map<String, Object> tripData) {
        return ResponseEntity.ok(agentOrchestrator.generateItinerary(tripData));
    }

    /**
     * Backlog #10: Modify existing itinerary via conversation.
     */
    @PostMapping("/modify-itinerary")
    public ResponseEntity<Map<String, Object>> modifyItinerary(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        Map<String, Object> currentItinerary = (Map<String, Object>) request.get("currentItinerary");
        String userRequest = (String) request.get("userRequest");
        return ResponseEntity.ok(agentOrchestrator.modifyItinerary(currentItinerary, userRequest));
    }

    /**
     * Backlog #9: books flight + hotel together for a previously generated/
     * modified itinerary. Body must match agent-ml-service's BookTripRequest
     * (itinerary, flightOfferId, hotelOfferId, passengerName, passengerDob,
     * email, plus optional origin/destination/date/latitude/longitude/
     * checkIn/checkOut/budget/guestNationality for stale-offer recovery).
     */
    @PostMapping("/book-trip")
    public ResponseEntity<Map<String, Object>> bookTrip(@RequestBody Map<String, Object> request) {
        return ResponseEntity.ok(agentOrchestrator.bookTrip(request));
    }
}
