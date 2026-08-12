package com.team7.mobile.business.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Checks whether the SecurityContext set on the request thread (by
 * JwtAuthFilter, in the real app) is still visible from inside
 * executeAsync()'s @Async worker thread - real TripService.agentChat() calls
 * currentUser.get()/getId() (via findOwnedTrip and saveConversationTurn),
 * which read SecurityContextHolder.getContext().getAuthentication(). Spring
 * Security's default SecurityContextHolder strategy is thread-local and does
 * NOT propagate to a new thread unless explicitly configured - and nothing in
 * this codebase configures that (confirmed: no
 * SecurityContextHolder.setStrategyName(MODE_INHERITABLETHREADLOCAL) and no
 * DelegatingSecurityContextAsyncTaskExecutor anywhere).
 */
@SpringBootTest(classes = {AgentChatService.class, com.team7.mobile.business.config.AsyncConfig.class})
class AgentChatServiceSecurityContextTest {

    @Autowired
    private AgentChatService agentChatService;

    @MockBean
    private TripService tripService;

    @Test
    void securityContextIsVisibleInsideAsyncWorker() throws InterruptedException {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, java.util.List.of()));

        AtomicReference<Object> authSeenInWorker = new AtomicReference<>("NOT_CAPTURED");
        CountDownLatch latch = new CountDownLatch(1);
        when(tripService.agentChat(anyLong(), any())).thenAnswer(invocation -> {
            authSeenInWorker.set(SecurityContextHolder.getContext().getAuthentication());
            latch.countDown();
            return Map.of("status", "ITINERARY_READY");
        });

        agentChatService.startTask(1L, "plan a trip");
        assertTrue(latch.await(5, TimeUnit.SECONDS), "executeAsync() never invoked tripService.agentChat()");

        SecurityContextHolder.clearContext();

        System.out.println("Authentication seen inside @Async worker thread: " + authSeenInWorker.get());
        assertNotNull(authSeenInWorker.get(),
                "SecurityContext is NOT visible inside the @Async worker thread - " +
                        "CurrentUser.get()/getId() inside the real TripService.agentChat() would fail here.");
    }
}
