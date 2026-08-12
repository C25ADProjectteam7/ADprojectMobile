package com.team7.mobile.business.service;

import com.team7.mobile.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Regression test for a real IDOR found via manual HTTP testing: getTask()
 * used to return any task by taskId with no check that the polling user
 * owns the trip the task belongs to - taskIds are unguessable UUIDs, but
 * that's not access control, and task results can carry another traveler's
 * itinerary/passenger PII. Fixed by routing the stored tripId through
 * TripService.assertTripOwnership() before returning the task.
 */
class AgentChatServiceOwnershipTest {

    private TripService tripService;
    private AgentChatService agentChatService;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        tripService = mock(TripService.class);
        agentChatService = new AgentChatService(tripService, null);

        Field tasksField = AgentChatService.class.getDeclaredField("tasks");
        tasksField.setAccessible(true);
        Map<String, Map<String, Object>> tasks =
                (Map<String, Map<String, Object>>) tasksField.get(agentChatService);
        tasks.put("task-owned-by-someone-else", Map.of(
                "status", "DONE",
                "tripId", 99L,
                "result", Map.of("itinerary", "someone else's trip")
        ));
    }

    @Test
    void getTaskChecksOwnershipOfTheTasksTrip() {
        agentChatService.getTask("task-owned-by-someone-else");

        verify(tripService).assertTripOwnership(eq(99L));
    }

    @Test
    void getTaskPropagatesOwnershipFailureInsteadOfLeakingTheResult() {
        doThrow(new BusinessException("FORBIDDEN", "Not authorized to access trip: 99", 403))
                .when(tripService).assertTripOwnership(99L);

        assertThrows(BusinessException.class,
                () -> agentChatService.getTask("task-owned-by-someone-else"),
                "A different user's task must not be readable just because the taskId is known.");
    }
}
