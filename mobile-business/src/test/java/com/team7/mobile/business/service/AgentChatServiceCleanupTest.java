package com.team7.mobile.business.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Regression test for AgentChatService's task-map cleanup: before this, DONE/
 * FAILED entries never expired, so the in-memory map grew unbounded for the
 * life of the JVM. No Spring context needed here (unlike AgentChatServiceAsyncTest) -
 * cleanupExpiredTasks() is plain synchronous logic over the task map, so a
 * bare instance plus reflection to seed backdated timestamps is enough
 * without waiting out the real 60-minute retention window.
 */
class AgentChatServiceCleanupTest {

    @Test
    void cleanupRemovesOnlyExpiredCompletedTasks() throws Exception {
        AgentChatService service = new AgentChatService(null, null);

        Field tasksField = AgentChatService.class.getDeclaredField("tasks");
        tasksField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> tasks = (Map<String, Map<String, Object>>) tasksField.get(service);

        long now = System.currentTimeMillis();
        long expired = now - (61 * 60 * 1000); // 61 min ago - past the 60-min retention window
        long recent = now - (5 * 60 * 1000);   // 5 min ago - still within the window

        tasks.put("expired-done", Map.of("status", "DONE", "finishedAt", expired));
        tasks.put("recent-done", Map.of("status", "DONE", "finishedAt", recent));
        tasks.put("still-processing", Map.of("status", "PROCESSING", "createdAt", expired));

        service.cleanupExpiredTasks();

        assertNull(tasks.get("expired-done"), "Expired DONE task should have been removed");
        assertNotNull(tasks.get("recent-done"), "Recent DONE task should NOT have been removed");
        assertNotNull(tasks.get("still-processing"),
                "PROCESSING task should never be removed regardless of age");
    }
}
