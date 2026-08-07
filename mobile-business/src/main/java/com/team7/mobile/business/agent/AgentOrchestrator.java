package com.team7.mobile.business.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team7.mobile.common.exception.ExternalApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Bridges Spring Boot with the Python FastAPI Agent service.
 * Calls 3 endpoints implemented by the Agent teammate on feature/agentic-ai:
 * 1. /extract-requirements - parse natural-language trip request (sync, fast)
 * 2. /generate-itinerary - produce full day-by-day itinerary (ASYNC - see
 * below)
 * 3. /modify-itinerary - conversational re-plan (ASYNC - see below)
 *
 * generate-itinerary and modify-itinerary can take 30s-2min to complete
 * (multiple real external API calls + two LLM passes), so they follow an
 * async task pattern: POST returns a taskId immediately, then this class
 * polls GET /tasks/{taskId} until the task reaches a terminal state.
 */
@Service
public class AgentOrchestrator {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String agentBaseUrl;

    // Polling configuration - tuned against real observed timings (full
    // itinerary generation: 150-215s observed; modification: ~65-90s observed)
    private static final int POLL_INTERVAL_MS = 3000;
    private static final int MAX_POLL_ATTEMPTS = 80; // 80 * 3s = 240s (4 min) ceiling

    public AgentOrchestrator(
            @Value("${app.agent.ml-service.url}") String agentBaseUrl,
            RestTemplate restTemplate,
            ObjectMapper objectMapper) {
        this.agentBaseUrl = agentBaseUrl;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Backlog #4: Extract structured trip requirements from free-text input.
     * Returns extracted fields + missingFields + clarifyingQuestion.
     * This call is fast (single LLM call) - stays synchronous.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> extractRequirements(String userInput) {
        String url = agentBaseUrl + "/api/agent/extract-requirements";
        Map<String, String> body = Map.of("userInput", userInput);
        return call(url, body);
    }

    /**
     * Backlog #6: Generate complete itinerary from structured trip data.
     * Async: submits the request, polls until the background task completes.
     */
    public Map<String, Object> generateItinerary(Map<String, Object> tripData) {
        String url = agentBaseUrl + "/api/agent/generate-itinerary";
        Map<String, Object> submitResponse = call(url, tripData);
        String taskId = (String) submitResponse.get("taskId");
        if (taskId == null) {
            throw new ExternalApiException("AgentService",
                    "generate-itinerary did not return a taskId: " + submitResponse, null);
        }
        return pollTask(taskId);
    }

    /**
     * Backlog #10: Modify an existing itinerary via conversational input.
     * Async: submits the request, polls until the background task completes.
     */
    public Map<String, Object> modifyItinerary(Map<String, Object> currentItinerary, String userRequest) {
        String url = agentBaseUrl + "/api/agent/modify-itinerary";
        Map<String, Object> body = Map.of(
                "currentItinerary", (Object) currentItinerary,
                "userRequest", userRequest);
        Map<String, Object> submitResponse = call(url, body);
        String taskId = (String) submitResponse.get("taskId");
        if (taskId == null) {
            throw new ExternalApiException("AgentService",
                    "modify-itinerary did not return a taskId: " + submitResponse, null);
        }
        return pollTask(taskId);
    }

    /**
     * Backlog #9: books flight + hotel together for a previously generated/
     * modified itinerary. Async: submits the request, polls until the
     * background task completes.
     */
    public Map<String, Object> bookTrip(Map<String, Object> bookingRequest) {
        String url = agentBaseUrl + "/api/agent/book-trip";
        Map<String, Object> submitResponse = call(url, bookingRequest);
        String taskId = (String) submitResponse.get("taskId");
        if (taskId == null) {
            throw new ExternalApiException("AgentService",
                    "book-trip did not return a taskId: " + submitResponse, null);
        }
        return pollTask(taskId);
    }

    /**
     * Polls GET /api/agent/tasks/{taskId} until the task's status is
     * "completed" or "failed", or MAX_POLL_ATTEMPTS is exceeded.
     * Task state shape: {"status": "processing"|"completed"|"failed",
     * "result": {...} | null, "error": "..." | null,
     * "createdAt": "...", "finishedAt": "..." | null}
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> pollTask(String taskId) {
        String url = agentBaseUrl + "/api/agent/tasks/" + taskId;

        for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ExternalApiException("AgentService", "Polling interrupted", e);
            }

            Map<String, Object> taskState;
            try {
                ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
                taskState = (Map<String, Object>) response.getBody();
            } catch (RestClientException e) {
                throw new ExternalApiException("AgentService",
                        "Failed to poll task " + taskId + ": " + e.getMessage(), e);
            }

            if (taskState == null) {
                throw new ExternalApiException("AgentService",
                        "Empty response polling task " + taskId, null);
            }

            String status = (String) taskState.get("status");
            if ("completed".equals(status)) {
                return (Map<String, Object>) taskState.get("result");
            }
            if ("failed".equals(status)) {
                throw new ExternalApiException("AgentService",
                        "Agent task " + taskId + " failed: " + taskState.get("error"), null);
            }
            // status is "processing" - loop and poll again
        }

        throw new ExternalApiException("AgentService",
                "Agent task " + taskId + " did not complete within "
                        + (MAX_POLL_ATTEMPTS * POLL_INTERVAL_MS / 1000) + " seconds",
                null);
    }

    /**
     * Shared POST helper - wraps transport errors as ExternalApiException (502).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> call(String url, Object body) {
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(url, jsonHeaders(body), Map.class);
            return (Map<String, Object>) response.getBody();
        } catch (RestClientException e) {
            throw new ExternalApiException("AgentService", url + " - " + e.getMessage(), e);
        }
    }

    private HttpEntity<String> jsonHeaders(Object body) {
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            return new HttpEntity<>(json, headers);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize request body", e);
        }
    }
}