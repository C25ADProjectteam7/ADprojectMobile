package com.team7.mobile.business.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team7.mobile.common.exception.ExternalApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
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
    // Polling is a read-only GET with no side effects, so a single transient
    // failure (dropped connection, brief network blip) shouldn't throw away
    // minutes of already-elapsed generation/booking progress. But a
    // persistently broken connection (service actually down) should still
    // fail well before the full MAX_POLL_ATTEMPTS budget is burned - so only
    // tolerate a handful of CONSECUTIVE failures, not unlimited ones.
    private static final int MAX_CONSECUTIVE_POLL_FAILURES = 3;

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
        return extractRequirements(userInput, List.of());
    }

    /**
     * Backlog #4 + conversation continuity: same as {@link #extractRequirements(String)},
     * but also sends prior turns of this trip's conversation (oldest first,
     * each a {"role": "user"|"assistant", "content": "..."} map) so the Agent
     * can resolve a follow-up like "make it 3000 instead" against a
     * destination/date mentioned earlier, instead of evaluating every message
     * in isolation.
     */
    public Map<String, Object> extractRequirements(String userInput, List<Map<String, String>> conversationHistory) {
        return extractRequirements(userInput, conversationHistory, null);
    }

    /**
     * Same as {@link #extractRequirements(String, List)}, plus a compressed
     * summary of turns older than what conversationHistory covers (see
     * TripService's conversation-summarization logic) - without this, facts
     * established many turns back would simply be lost once they age out of
     * the verbatim history window.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> extractRequirements(String userInput, List<Map<String, String>> conversationHistory,
                                                     String conversationSummary) {
        String url = agentBaseUrl + "/api/agent/extract-requirements";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userInput", userInput);
        body.put("conversationHistory", conversationHistory != null ? conversationHistory : List.of());
        body.put("conversationSummary", conversationSummary);
        return call(url, body);
    }

    /**
     * Compresses conversation turns that have aged out of the recent
     * verbatim window into a short running summary. Incremental: pass the
     * existing summary (if any) plus only the newly-dropped turns since it
     * was last computed - not the entire history every time. Sync, not a
     * background task - summarizing a handful of short chat turns is fast.
     */
    @SuppressWarnings("unchecked")
    public String summarizeConversation(String previousSummary, List<Map<String, String>> turns) {
        String url = agentBaseUrl + "/api/agent/summarize-conversation";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("previousSummary", previousSummary);
        body.put("turns", turns);
        Map<String, Object> response = call(url, body);
        return (String) response.get("summary");
    }

    /**
     * Backlog #6: Generate complete itinerary from structured trip data.
     * Async: submits the request, polls until the background task completes.
     * stageListener (optional): called with the Python task's stage on every
     * poll so the app can show real progress instead of a generic spinner.
     */
    public Map<String, Object> generateItinerary(Map<String, Object> tripData,
                                                 java.util.function.Consumer<String> stageListener) {
        String url = agentBaseUrl + "/api/agent/generate-itinerary";
        Map<String, Object> submitResponse = call(url, tripData);
        String taskId = (String) submitResponse.get("taskId");
        if (taskId == null) {
            throw new ExternalApiException("AgentService",
                    "generate-itinerary did not return a taskId: " + submitResponse, null);
        }
        return pollTask(taskId, stageListener);
    }

    /** No-stage-listener overload — keeps existing callers working unchanged. */
    public Map<String, Object> generateItinerary(Map<String, Object> tripData) {
        return generateItinerary(tripData, null);
    }

    /**
     * Backlog #10: Modify an existing itinerary via conversational input.
     * Async: submits the request, polls until the background task completes.
     */
    public Map<String, Object> modifyItinerary(Map<String, Object> currentItinerary, String userRequest,
                                               java.util.function.Consumer<String> stageListener) {
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
        return pollTask(taskId, stageListener);
    }

    /** No-stage-listener overload — keeps existing callers working unchanged. */
    public Map<String, Object> modifyItinerary(Map<String, Object> currentItinerary, String userRequest) {
        return modifyItinerary(currentItinerary, userRequest, null);
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
        return pollTask(taskId, null);
    }

    /**
     * Polls GET /api/agent/tasks/{taskId} until the task's status is
     * "completed" or "failed", or MAX_POLL_ATTEMPTS is exceeded.
     * Task state shape: {"status": "processing"|"completed"|"failed",
     * "result": {...} | null, "error": "..." | null,
     * "createdAt": "...", "finishedAt": "..." | null}
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> pollTask(String taskId, java.util.function.Consumer<String> stageListener) {
        String url = agentBaseUrl + "/api/agent/tasks/" + taskId;
        int consecutiveFailures = 0;

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
                consecutiveFailures = 0;
            } catch (RestClientException e) {
                consecutiveFailures++;
                if (consecutiveFailures >= MAX_CONSECUTIVE_POLL_FAILURES) {
                    throw new ExternalApiException("AgentService",
                            "Failed to poll task " + taskId + " after " + consecutiveFailures
                                    + " consecutive attempts: " + e.getMessage(), e);
                }
                continue;
            }

            if (taskState == null) {
                throw new ExternalApiException("AgentService",
                        "Empty response polling task " + taskId, null);
            }

            String status = (String) taskState.get("status");
            // Push the Python task's current stage to the caller (e.g. the
            // app-visible task map in AgentChatService) so progress is real:
            // "searching_flights_hotels" beats a generic spinner.
            if (stageListener != null && taskState.get("stage") != null) {
                stageListener.accept((String) taskState.get("stage"));
            }
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