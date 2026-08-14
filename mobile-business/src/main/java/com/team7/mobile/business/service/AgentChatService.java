package com.team7.mobile.business.service;

import com.team7.mobile.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Iterator;
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

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    // Shown to the API caller when a background task fails. BusinessException
    // messages (e.g. "Trip not found: 5") are already written to be
    // user-facing, so those pass through as-is; anything else (NPE, DB
    // errors, a raw library exception whose message might embed request
    // details) is replaced with this generic text - the real exception always
    // goes to the server log via log.error(...) below, keyed by taskId.
    private static final String GENERIC_FAILURE_MESSAGE =
            "The request could not be completed due to an internal error. "
                    + "Please try again; if this persists, contact support and reference this taskId.";

    public enum TaskStatus { PROCESSING, DONE, FAILED }

    /** taskId → {status, result/error, tripId, createdAt, finishedAt} */
    private final Map<String, Map<String, Object>> tasks = new ConcurrentHashMap<>();

    // How long a DONE/FAILED task stays queryable before cleanupExpiredTasks()
    // removes it - mirrors agent-ml-service's task_manager.py TASK_RETENTION_MINUTES,
    // so this in-memory map doesn't grow unbounded for the lifetime of the JVM.
    // PROCESSING tasks are never removed, regardless of age.
    private static final long TASK_RETENTION_MINUTES = 60;

    private final TripService tripService;
    // Self-injected proxy: @Async only takes effect when the annotated method
    // is invoked through the Spring-managed proxy. Calling executeAsync(...)
    // directly (this.executeAsync(...)) bypasses the proxy and runs
    // synchronously, silently defeating the "return taskId immediately"
    // contract this class exists for. @Lazy avoids a circular-construction
    // error from injecting the bean into itself.
    private final AgentChatService self;

    public AgentChatService(TripService tripService, @Lazy AgentChatService self) {
        this.tripService = tripService;
        this.self = self;
    }

    /**
     * Start an async agent-chat (generate) task and return its taskId immediately.
     */
    public String startTask(Long tripId, String message) {
        String taskId = newTask(tripId);
        self.executeAsync(taskId, tripId, message);
        return taskId;
    }

    /**
     * Start an async agent-modify task and return its taskId immediately.
     * Same task-map/polling contract as startTask() - modify_itinerary is
     * just as slow (~65-130s observed) as generation, so it gets the same
     * "return taskId now, poll for the result" treatment.
     */
    public String startModifyTask(Long tripId, String userRequest) {
        String taskId = newTask(tripId);
        self.executeModifyAsync(taskId, tripId, userRequest);
        return taskId;
    }

    private String newTask(Long tripId) {
        String taskId = UUID.randomUUID().toString();
        tasks.put(taskId, Map.of(
                "status", TaskStatus.PROCESSING.name(),
                "tripId", tripId,
                "createdAt", System.currentTimeMillis()
        ));
        return taskId;
    }

    /**
     * Poll result of a task. Task results can contain another traveler's full
     * itinerary and, after a booking, passenger PII - taskIds are unguessable
     * UUIDs, but that alone isn't access control, so this still checks that
     * the polling user owns the trip the task was created for.
     */
    public Map<String, Object> getTask(String taskId) {
        Map<String, Object> task = tasks.get(taskId);
        if (task == null) {
            throw new BusinessException("TASK_NOT_FOUND", "Unknown task: " + taskId, 404);
        }
        tripService.assertTripOwnership((Long) task.get("tripId"));
        return task;
    }

    @Async
    public void executeAsync(String taskId, Long tripId, String message) {
        runTaskAsync(taskId, tripId,
                () -> tripService.agentChat(tripId, message, stage -> updateStage(taskId, stage)));
    }

    @Async
    public void executeModifyAsync(String taskId, Long tripId, String userRequest) {
        runTaskAsync(taskId, tripId,
                () -> tripService.modifyItinerary(tripId, userRequest, stage -> updateStage(taskId, stage)));
    }

    /** Updates the task's stage field so GET /api/agent/tasks/{taskId} shows
     * real progress (e.g. "searching_flights_hotels") to the polling app. */
    private void updateStage(String taskId, String stage) {
        Map<String, Object> existing = tasks.get(taskId);
        if (existing != null) {
            Map<String, Object> updated = new HashMap<>(existing);
            updated.put("stage", stage);
            tasks.put(taskId, updated);
        }
    }

    private void runTaskAsync(String taskId, Long tripId, java.util.function.Supplier<Map<String, Object>> work) {
        try {
            Map<String, Object> result = work.get();
            completeTask(taskId, TaskStatus.DONE, "result", result);
        } catch (Exception e) {
            log.error("Agent task {} (trip {}) failed", taskId, tripId, e);
            String clientMessage = (e instanceof BusinessException)
                    ? e.getMessage()
                    : GENERIC_FAILURE_MESSAGE;
            completeTask(taskId, TaskStatus.FAILED, "error", clientMessage);
        }
    }

    /**
     * Marks a task DONE/FAILED, preserving its original tripId/createdAt
     * (rather than replacing the whole map entry) and stamping finishedAt -
     * cleanupExpiredTasks() needs that to know how long a completed task has
     * been sitting around.
     */
    private void completeTask(String taskId, TaskStatus status, String resultKey, Object resultValue) {
        Map<String, Object> existing = tasks.get(taskId);
        Map<String, Object> updated = existing != null ? new HashMap<>(existing) : new HashMap<>();
        updated.put("status", status.name());
        updated.put(resultKey, resultValue);
        updated.put("finishedAt", System.currentTimeMillis());
        tasks.put(taskId, updated);
    }

    /**
     * Removes DONE/FAILED tasks older than TASK_RETENTION_MINUTES so this
     * in-memory map doesn't grow unbounded. Mirrors agent-ml-service's
     * task_manager.cleanup_expired_tasks(); runs every 10 minutes.
     */
    @Scheduled(fixedRate = 10 * 60 * 1000)
    public void cleanupExpiredTasks() {
        long cutoff = System.currentTimeMillis() - TASK_RETENTION_MINUTES * 60 * 1000;
        int removed = 0;
        for (Iterator<Map.Entry<String, Map<String, Object>>> it = tasks.entrySet().iterator(); it.hasNext(); ) {
            Map<String, Object> task = it.next().getValue();
            String status = (String) task.get("status");
            if (!TaskStatus.DONE.name().equals(status) && !TaskStatus.FAILED.name().equals(status)) {
                continue;
            }
            Object finishedAt = task.get("finishedAt");
            if (!(finishedAt instanceof Long) || (Long) finishedAt >= cutoff) {
                continue;
            }
            it.remove();
            removed++;
        }
        if (removed > 0) {
            log.info("Cleaned up {} expired agent-chat task(s)", removed);
        }
    }
}
