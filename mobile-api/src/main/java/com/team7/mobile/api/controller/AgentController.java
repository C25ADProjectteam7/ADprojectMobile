package com.team7.mobile.api.controller;

import com.team7.mobile.business.agent.AgentOrchestrator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentOrchestrator agentOrchestrator;

    public AgentController(AgentOrchestrator agentOrchestrator) {
        this.agentOrchestrator = agentOrchestrator;
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
}
