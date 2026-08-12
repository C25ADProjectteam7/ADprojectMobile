package com.team7.mobile.business.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team7.mobile.business.agent.AgentOrchestrator;
import com.team7.mobile.business.util.CurrentUser;
import com.team7.mobile.common.exception.BusinessException;
import com.team7.mobile.data.entity.AgentConversation;
import com.team7.mobile.data.entity.Trip;
import com.team7.mobile.data.entity.User;
import com.team7.mobile.data.repository.AgentConversationRepository;
import com.team7.mobile.data.repository.ItineraryItemRepository;
import com.team7.mobile.data.repository.ItineraryRepository;
import com.team7.mobile.data.repository.TripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain Mockito test (no Spring context needed - this is checking business
 * logic, not @Async/proxy behavior) for TripService.modifyItinerary():
 * the JSON round-trip through Trip.agentItineraryJson, and the "no itinerary
 * yet" guard - modify_itinerary needs the FULL raw itinerary JSON (totalCost,
 * warnings, etc.) that only a prior agentChat() call produces and stores.
 */
class TripServiceModifyItineraryTest {

    private TripRepository tripRepository;
    private AgentConversationRepository agentConversationRepository;
    private CurrentUser currentUser;
    private AgentOrchestrator agentOrchestrator;
    private TripService tripService;
    private Trip trip;
    private User user;

    @BeforeEach
    void setUp() throws Exception {
        tripRepository = mock(TripRepository.class);
        agentConversationRepository = mock(AgentConversationRepository.class);
        currentUser = mock(CurrentUser.class);
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
        // saveItinerary()'s date fallback (day.getOrDefault("date", trip.getStartDate().toString()))
        // evaluates trip.getStartDate() eagerly regardless of whether "date" is
        // present - unrelated to what's under test here, just needs to be non-null.
        trip.setStartDate(java.time.LocalDate.of(2026, 9, 10));

        when(currentUser.get()).thenReturn(user);
        when(currentUser.getId()).thenReturn(1L);
        when(tripRepository.findById(42L)).thenReturn(Optional.of(trip));
        when(agentConversationRepository.findByUserIdAndTripIdOrderByCreatedAtAsc(1L, 42L))
                .thenReturn(List.of());
    }

    /** JPA entities here only expose an auto-generated id via getter, no setter. */
    private static void setId(Object entity, Long id) throws Exception {
        java.lang.reflect.Field field = entity.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    @Test
    void throwsWhenNoItineraryExistsYet() {
        // trip.agentItineraryJson is null - never generated an itinerary
        BusinessException ex = assertThrows(BusinessException.class,
                () -> tripService.modifyItinerary(42L, "find me a cheaper hotel"));
        assertEquals("NO_ITINERARY", ex.getErrorCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void roundTripsStoredItineraryJsonThroughModifyItinerary() throws Exception {
        trip.setAgentItineraryJson("{\"day1\":{\"date\":\"2026-09-10\"},\"totalCostSGD\":500.0,\"warnings\":[]}");

        Map<String, Object> updated = Map.of(
                "day1", Map.of("date", "2026-09-10"),
                "totalCostSGD", 450.0,
                "warnings", List.of("Switched to a cheaper hotel per your request.")
        );
        when(agentOrchestrator.modifyItinerary(any(), eq("find me a cheaper hotel"))).thenReturn(updated);

        Map<String, Object> result = tripService.modifyItinerary(42L, "find me a cheaper hotel");

        assertEquals("ITINERARY_UPDATED", result.get("status"));
        assertEquals(updated, result.get("itinerary"));

        // The exact Map deserialized from the stored JSON must be what got
        // passed to modify_itinerary (its totalCostSGD/warnings included) -
        // confirms the round trip actually carries the full itinerary, not a
        // reconstruction from the lossy Itinerary/ItineraryItem rows.
        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(agentOrchestrator).modifyItinerary(captor.capture(), eq("find me a cheaper hotel"));
        Map<String, Object> passedIn = captor.getValue();
        assertEquals(500.0, passedIn.get("totalCostSGD"));

        // The trip's stored itinerary JSON must be updated to the new result.
        assertEquals(new ObjectMapper().valueToTree(updated).toString(),
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(trip.getAgentItineraryJson()).toString());

        // Both a USER and an ASSISTANT conversation turn should have been recorded.
        var roleCaptor = org.mockito.ArgumentCaptor.forClass(AgentConversation.class);
        verify(agentConversationRepository, org.mockito.Mockito.times(2)).save(roleCaptor.capture());
        List<AgentConversation> saved = roleCaptor.getAllValues();
        assertEquals(AgentConversation.Role.USER, saved.get(0).getRole());
        assertEquals(AgentConversation.Role.ASSISTANT, saved.get(1).getRole());
    }
}
