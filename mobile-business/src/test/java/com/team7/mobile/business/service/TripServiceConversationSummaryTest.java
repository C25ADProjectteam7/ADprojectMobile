package com.team7.mobile.business.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team7.mobile.business.agent.AgentOrchestrator;
import com.team7.mobile.business.util.CurrentUser;
import com.team7.mobile.data.entity.AgentConversation;
import com.team7.mobile.data.entity.Trip;
import com.team7.mobile.data.entity.User;
import com.team7.mobile.data.repository.AgentConversationRepository;
import com.team7.mobile.data.repository.ItineraryItemRepository;
import com.team7.mobile.data.repository.ItineraryRepository;
import com.team7.mobile.data.repository.TripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain Mockito test (no Spring context needed) for TripService's incremental
 * conversation-summarization logic (buildConversationContext(), exercised via
 * the public agentChat()): once a trip's conversation exceeds
 * MAX_HISTORY_TURNS (20) turns, anything older than the recent window must be
 * folded into Trip.conversationSummary instead of silently dropped - and
 * that folding must be incremental (only the newly-dropped delta, not the
 * whole older history) and cached (no redundant summarize calls once the
 * window hasn't moved since the last summarization).
 */
class TripServiceConversationSummaryTest {

    private TripRepository tripRepository;
    private AgentConversationRepository agentConversationRepository;
    private AgentOrchestrator agentOrchestrator;
    private TripService tripService;
    private Trip trip;
    private User user;

    @BeforeEach
    void setUp() throws Exception {
        tripRepository = mock(TripRepository.class);
        agentConversationRepository = mock(AgentConversationRepository.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        agentOrchestrator = mock(AgentOrchestrator.class);

        tripService = new TripService(
                tripRepository,
                mock(ItineraryRepository.class),
                mock(ItineraryItemRepository.class),
                agentConversationRepository,
                currentUser,
                agentOrchestrator,
                mock(BookingService.class),
                new ObjectMapper()
        );

        user = new User();
        setId(user, 1L);

        trip = new Trip();
        setId(trip, 42L);
        trip.setUser(user);

        when(currentUser.get()).thenReturn(user);
        when(currentUser.getId()).thenReturn(1L);
        when(tripRepository.findById(42L)).thenReturn(Optional.of(trip));
        when(agentConversationRepository.findByUserIdAndTripIdOrderByCreatedAtDesc(eq(1L), eq(42L), any()))
                .thenReturn(new ArrayList<>());

        // Short-circuits agentChat() right after Step 1, so these tests don't
        // also need to mock itinerary generation/persistence.
        when(agentOrchestrator.extractRequirements(anyString(), any(), any()))
                .thenReturn(Map.of("missingFields", List.of("budgetTotal"), "clarifyingQuestion", "What's your budget?"));
    }

    private static void setId(Object entity, Long id) throws Exception {
        Field field = entity.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    private static List<AgentConversation> turns(int count) throws Exception {
        List<AgentConversation> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            AgentConversation turn = new AgentConversation();
            turn.setRole(AgentConversation.Role.USER);
            turn.setContent("turn " + i);
            list.add(turn);
        }
        return list;
    }

    @Test
    void belowThresholdNeverSummarizes() {
        when(agentConversationRepository.countByUserIdAndTripId(1L, 42L)).thenReturn(5L);

        tripService.agentChat(42L, "hello");

        verify(agentOrchestrator, never()).summarizeConversation(any(), any());
        assertNull(trip.getConversationSummarizedThroughCount());
    }

    @Test
    void firstTimeOverThresholdSummarizesTheDroppedTurns() throws Exception {
        when(agentConversationRepository.countByUserIdAndTripId(1L, 42L)).thenReturn(25L);
        when(agentConversationRepository.findByUserIdAndTripIdOrderByCreatedAtAsc(eq(1L), eq(42L), any(Pageable.class)))
                .thenReturn(turns(5));
        when(agentOrchestrator.summarizeConversation(isNull(), any())).thenReturn("SUMMARY_A");

        tripService.agentChat(42L, "hello");

        verify(agentOrchestrator).summarizeConversation(isNull(), any());
        assertEquals("SUMMARY_A", trip.getConversationSummary());
        assertEquals(5, trip.getConversationSummarizedThroughCount());
        verify(agentOrchestrator).extractRequirements(eq("hello"), any(), eq("SUMMARY_A"));
    }

    @Test
    void cachedSummaryIsReusedWithoutRecomputing() {
        trip.setConversationSummary("SUMMARY_A");
        trip.setConversationSummarizedThroughCount(5);
        when(agentConversationRepository.countByUserIdAndTripId(1L, 42L)).thenReturn(25L); // windowStart still 5

        tripService.agentChat(42L, "hello");

        verify(agentOrchestrator, never()).summarizeConversation(any(), any());
        verify(agentOrchestrator).extractRequirements(eq("hello"), any(), eq("SUMMARY_A"));
    }

    @Test
    void windowAdvancingExtendsTheSummaryIncrementally() throws Exception {
        trip.setConversationSummary("SUMMARY_A");
        trip.setConversationSummarizedThroughCount(5);
        when(agentConversationRepository.countByUserIdAndTripId(1L, 42L)).thenReturn(30L); // windowStart now 10
        // The oldest-10 fetch; buildConversationContext should only forward the
        // last 5 of these (indices 5-9) as the "newly dropped" delta.
        when(agentConversationRepository.findByUserIdAndTripIdOrderByCreatedAtAsc(eq(1L), eq(42L), any(Pageable.class)))
                .thenReturn(turns(10));
        when(agentOrchestrator.summarizeConversation(eq("SUMMARY_A"), any())).thenReturn("SUMMARY_B");

        tripService.agentChat(42L, "hello");

        // Must be exactly the 5 NEW turns (indices 5-9), not all 10 - proves
        // this is incremental, not a full resummarization from scratch.
        var deltaCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(agentOrchestrator).summarizeConversation(eq("SUMMARY_A"), deltaCaptor.capture());
        List<Map<String, String>> delta = deltaCaptor.getValue();
        assertEquals(5, delta.size(), "Expected only the newly-dropped 5 turns, not the full older-than-window set");
        assertEquals("turn 5", delta.get(0).get("content"));
        assertEquals("turn 9", delta.get(4).get("content"));

        assertEquals("SUMMARY_B", trip.getConversationSummary());
        assertEquals(10, trip.getConversationSummarizedThroughCount());
    }
}
