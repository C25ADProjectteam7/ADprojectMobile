package com.team7.mobile.business.service;

import com.team7.mobile.common.exception.BusinessException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Async Agent conversation execution.
 * <p>
 * generate-itinerary can take tens of seconds (LLM + multiple external API calls),
 * which would blow past typical HTTP client timeouts. So:
 *   POST  /api/trips/{id}/agent-chat → returns immediately with a taskId
 *   GET   /api/agent/tasks/{taskId}  → frontend polls; returns PROCESSING until done
 * <p>
 * Tasks live in an in-memory map — fine for a school project / single server.
 * (Would need Redis + persistent queue for production.)
 */
@Service
public class AgentChatService {

    public enum TaskStatus { PROCESSING, DONE, FAILED }

    /** taskId → {status, result/error, tripId, createdAt} */
    private final Map<String, Map<String, Object>> tasks = new ConcurrentHashMap<>();

    private final TripService tripService;

    public AgentChatService(TripService tripService) {
        this.tripService = tripService;
    }

    /**
     * Start an async agent-chat task and return its taskId immediately.
     */
    public String startTask(Long tripId, String message) {
        String taskId = UUID.randomUUID().toString();
        tasks.put(taskId, Map.of(
                "status", TaskStatus.PROCESSING.name(),
                "tripId", tripId,
                "createdAt", System.currentTimeMillis()
        ));
        executeAsync(taskId, tripId, message);
        return taskId;
    }

    /** Poll result of a task. */
    public Map<String, Object> getTask(String taskId) {
        Map<String, Object> task = tasks.get(taskId);
        if (task == null) {
            throw new BusinessException("TASK_NOT_FOUND", "Unknown task: " + taskId, 404);
        }
        return task;
    }

    @Async
    protected void executeAsync(String taskId, Long tripId, String message) {
        try {
            Map<String, Object> result = tripService.agentChat(tripId, message);
            tasks.put(taskId, Map.of(
                    "status", TaskStatus.DONE.name(),
                    "result", result
            ));
        } catch (Exception e) {
            tasks.put(taskId, Map.of(
                    "status", TaskStatus.FAILED.name(),
                    "error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()
            ));
        }
    }
}
