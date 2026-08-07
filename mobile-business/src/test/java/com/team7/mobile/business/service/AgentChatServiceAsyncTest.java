package com.team7.mobile.business.service;

import com.team7.mobile.business.config.AsyncConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Regression test for the @Async self-invocation bug: startTask() used to call
 * executeAsync(...) via `this`, bypassing the Spring AOP proxy that makes
 * @Async take effect, so the "slow" agent work ran synchronously and startTask()
 * blocked until it finished. Fixed by calling executeAsync() through an
 * injected self-proxy instead.
 * <p>
 * Needs a real Spring context (not a plain Mockito unit test) because the bug
 * is specifically about proxy-vs-self-invocation - a bare `new AgentChatService(...)`
 * would never exhibit it either way.
 */
@SpringBootTest(classes = {AgentChatService.class, AsyncConfig.class})
class AgentChatServiceAsyncTest {

    @Autowired
    private AgentChatService agentChatService;

    @MockBean
    private TripService tripService;

    @Test
    void startTaskReturnsBeforeSlowAgentChatCompletes() throws InterruptedException {
        long simulatedWorkMillis = 2000;
        when(tripService.agentChat(anyLong(), any())).thenAnswer(invocation -> {
            Thread.sleep(simulatedWorkMillis);
            return Map.of("status", "ITINERARY_READY");
        });

        long startNanos = System.nanoTime();
        String taskId = agentChatService.startTask(1L, "plan a trip to Singapore");
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        assertNotNull(taskId);
        assertTrue(elapsedMillis < simulatedWorkMillis / 2,
                "startTask() took " + elapsedMillis + "ms to return, expected it to return "
                        + "almost immediately. If this fails, executeAsync() is running "
                        + "synchronously again (e.g. a self-invocation regression).");

        // Right after startTask() returns, the mocked agentChat() call is still
        // sleeping - the task must still be PROCESSING.
        assertEquals("PROCESSING", agentChatService.getTask(taskId).get("status"));

        // Once the background work has had time to finish, the task should be DONE.
        Thread.sleep(simulatedWorkMillis + 500);
        assertEquals("DONE", agentChatService.getTask(taskId).get("status"));
    }
}
