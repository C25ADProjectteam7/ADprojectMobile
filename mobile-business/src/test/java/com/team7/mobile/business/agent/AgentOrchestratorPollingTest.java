package com.team7.mobile.business.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team7.mobile.common.exception.ExternalApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for pollTask()'s handling of transient poll failures.
 * Polling GET /tasks/{taskId} has no side effects, so one dropped connection
 * out of up to 80 poll attempts (over up to 4 minutes) shouldn't throw away
 * an already-mostly-elapsed itinerary generation just because that single
 * GET failed - but a persistently broken connection should still fail fast,
 * not silently burn the entire poll budget before giving up.
 */
class AgentOrchestratorPollingTest {

    private AgentOrchestrator newOrchestrator(RestTemplate restTemplate) {
        return new AgentOrchestrator("http://agent-ml:8000", restTemplate, new ObjectMapper());
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> taskState(String status, Object result) {
        return ResponseEntity.ok(Map.of("status", status, "result", result == null ? Map.of() : result));
    }

    @Test
    @SuppressWarnings("unchecked")
    void transientPollFailuresAreRetriedNotFatal() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForEntity(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("taskId", "task-1")));
        when(restTemplate.getForEntity(anyString(), org.mockito.ArgumentMatchers.eq(Map.class)))
                .thenThrow(new ResourceAccessException("connection reset"))
                .thenThrow(new ResourceAccessException("connection reset"))
                .thenReturn(taskState("completed", Map.of("itinerary", "ok")));

        AgentOrchestrator orchestrator = newOrchestrator(restTemplate);
        Map<String, Object> result = orchestrator.generateItinerary(Map.of("destination", "Singapore"));

        assertEquals("ok", result.get("itinerary"));
        verify(restTemplate, times(3)).getForEntity(anyString(), org.mockito.ArgumentMatchers.eq(Map.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void persistentPollFailuresFailFastNotAfterTheFullBudget() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForEntity(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("taskId", "task-2")));
        when(restTemplate.getForEntity(anyString(), org.mockito.ArgumentMatchers.eq(Map.class)))
                .thenThrow(new ResourceAccessException("connection refused"));

        AgentOrchestrator orchestrator = newOrchestrator(restTemplate);

        assertThrows(ExternalApiException.class,
                () -> orchestrator.generateItinerary(Map.of("destination", "Singapore")));

        // Exactly MAX_CONSECUTIVE_POLL_FAILURES (3) - proves this fails fast
        // instead of burning through all 80 poll attempts on a dead connection.
        verify(restTemplate, times(3)).getForEntity(anyString(), org.mockito.ArgumentMatchers.eq(Map.class));
    }
}
