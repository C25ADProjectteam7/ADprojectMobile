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
 * 1. /extract-requirements   — parse natural-language trip request
 * 2. /generate-itinerary     — produce full day-by-day itinerary
 * 3. /modify-itinerary       — conversational re-plan
 */
@Service
public class AgentOrchestrator {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String agentBaseUrl;

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
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> extractRequirements(String userInput) {
        String url = agentBaseUrl + "/api/agent/extract-requirements";
        Map<String, String> body = Map.of("userInput", userInput);
        return call(url, body);
    }

    /**
     * Backlog #6: Generate complete itinerary from structured trip data.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> generateItinerary(Map<String, Object> tripData) {
        String url = agentBaseUrl + "/api/agent/generate-itinerary";
        return call(url, tripData);
    }

    /**
     * Backlog #10: Modify an existing itinerary via conversational input.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> modifyItinerary(Map<String, Object> currentItinerary, String userRequest) {
        String url = agentBaseUrl + "/api/agent/modify-itinerary";
        Map<String, Object> body = Map.of(
                "currentItinerary", (Object) currentItinerary,
                "userRequest", userRequest
        );
        return call(url, body);
    }

    /** Shared POST helper — wraps transport errors as ExternalApiException (502). */
    @SuppressWarnings("unchecked")
    private Map<String, Object> call(String url, Object body) {
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(url, jsonHeaders(body), Map.class);
            return (Map<String, Object>) response.getBody();
        } catch (RestClientException e) {
            throw new ExternalApiException("AgentService", url + " — " + e.getMessage(), e);
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
