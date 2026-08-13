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
    private ItineraryItemRepository itineraryItemRepository;
    private CurrentUser currentUser;
    private AgentOrchestrator agentOrchestrator;
    private TripService tripService;
    private Trip trip;
    private User user;

    @BeforeEach
    void setUp() throws Exception {
        tripRepository = mock(TripRepository.class);
        agentConversationRepository = mock(AgentConversationRepository.class);
        itineraryItemRepository = mock(ItineraryItemRepository.class);
        currentUser = mock(CurrentUser.class);
        agentOrchestrator = mock(AgentOrchestrator.class);

        tripService = new TripService(
                tripRepository,
                mock(ItineraryRepository.class),
                itineraryItemRepository,
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

    /**
     * Regression test for a real UX bug found via manual reproduction: when
     * the Agent can't find anything better (e.g. "a more expensive hotel"
     * but no pricier option exists in range), it correctly keeps the
     * itinerary unchanged and says so in warnings - but the response used to
     * always claim status "ITINERARY_UPDATED" and prefix the summary with
     * "I've updated the itinerary: ", producing a self-contradictory message.
     * changeApplied:false should surface as its own status, and the summary
     * should be the Agent's own honest note verbatim, not stapled behind a
     * false claim that something changed.
     */
    @Test
    @SuppressWarnings("unchecked")
    void changeAppliedFalseSurfacesAsNoChangeFoundWithHonestSummary() throws Exception {
        trip.setAgentItineraryJson("{\"day1\":{\"date\":\"2026-09-10\"},\"totalCostSGD\":500.0,\"warnings\":[]}");

        String honestNote = "No more expensive hotel was found in the available search results. "
                + "The original hotel (Hotel 81 Kovan) remains unchanged.";
        Map<String, Object> updated = Map.of(
                "day1", Map.of("date", "2026-09-10"),
                "totalCostSGD", 500.0,
                "warnings", List.of(honestNote),
                "changeApplied", false
        );
        when(agentOrchestrator.modifyItinerary(any(), eq("find me a more expensive hotel")))
                .thenReturn(updated);

        Map<String, Object> result = tripService.modifyItinerary(42L, "find me a more expensive hotel");

        assertEquals("NO_CHANGE_FOUND", result.get("status"));

        var roleCaptor = org.mockito.ArgumentCaptor.forClass(AgentConversation.class);
        verify(agentConversationRepository, org.mockito.Mockito.times(2)).save(roleCaptor.capture());
        AgentConversation assistantTurn = roleCaptor.getAllValues().get(1);
        assertEquals(AgentConversation.Role.ASSISTANT, assistantTurn.getRole());
        assertEquals(honestNote, assistantTurn.getContent(),
                "Summary should be the Agent's own honest note verbatim, not prefixed with a false "
                        + "\"I've updated the itinerary\" claim.");
    }

    /**
     * Same bug class as changeAppliedFalseSurfacesAsNoChangeFoundWithHonestSummary,
     * but for the case that fix didn't originally cover: changeApplied:false
     * with an EMPTY warnings list. The fallback summary text used to be
     * hardcoded to "I've updated the itinerary as requested.", contradicting
     * a NO_CHANGE_FOUND status right next to it.
     * <p>
     * orchestrator.py's _validate_and_normalize_itinerary now rejects this
     * shape outright (changeApplied=false requires non-empty warnings,
     * retried there rather than ever reaching Java) - so this scenario
     * shouldn't occur against a current agent-ml-service build. Kept as
     * defense-in-depth for an older/misbehaving build, same rationale as the
     * changeApplied-missing fallback a few lines above.
     */
    @Test
    void changeAppliedFalseWithNoWarningsStillGetsAnHonestFallbackSummary() throws Exception {
        trip.setAgentItineraryJson("{\"day1\":{\"date\":\"2026-09-10\"},\"totalCostSGD\":500.0,\"warnings\":[]}");

        Map<String, Object> updated = Map.of(
                "day1", Map.of("date", "2026-09-10"),
                "totalCostSGD", 500.0,
                "warnings", List.of(),
                "changeApplied", false
        );
        when(agentOrchestrator.modifyItinerary(any(), eq("find me a more expensive hotel")))
                .thenReturn(updated);

        Map<String, Object> result = tripService.modifyItinerary(42L, "find me a more expensive hotel");

        assertEquals("NO_CHANGE_FOUND", result.get("status"));

        var roleCaptor = org.mockito.ArgumentCaptor.forClass(AgentConversation.class);
        verify(agentConversationRepository, org.mockito.Mockito.times(2)).save(roleCaptor.capture());
        AgentConversation assistantTurn = roleCaptor.getAllValues().get(1);
        assertEquals(AgentConversation.Role.ASSISTANT, assistantTurn.getRole());
        org.junit.jupiter.api.Assertions.assertFalse(
                assistantTurn.getContent().toLowerCase(java.util.Locale.ROOT).contains("updated"),
                "Fallback summary must not claim the itinerary was updated when status is NO_CHANGE_FOUND: "
                        + assistantTurn.getContent());
    }

    /**
     * Regression test for a real bug found via a live generated itinerary:
     * Agent flight objects carry "departureTime"/"arrivalTime" (Duffel's own
     * field names, preserved verbatim), never "startTime"/"endTime". saveItem()
     * used to read only "startTime"/"endTime", so every persisted flight
     * ItineraryItem silently got startTime=null/endTime=null even though the
     * itinerary JSON had a real departure/arrival time right there.
     */
    @Test
    void savedFlightItemFallsBackToDepartureAndArrivalTimeFields() throws Exception {
        trip.setAgentItineraryJson("{\"day1\":{\"date\":\"2026-09-10\"},\"totalCostSGD\":500.0,\"warnings\":[]}");

        Map<String, Object> flight = Map.of(
                "offerId", "off_test123",
                "flightNumber", "CZ3192",
                "departureTime", "2026-09-10T10:00:00",
                "arrivalTime", "2026-09-10T19:40:00"
        );
        Map<String, Object> updated = Map.of(
                "day1", Map.of("date", "2026-09-10", "flight", flight),
                "totalCostSGD", 500.0,
                "warnings", List.of(),
                "changeApplied", true
        );
        when(agentOrchestrator.modifyItinerary(any(), eq("move my flight earlier")))
                .thenReturn(updated);

        tripService.modifyItinerary(42L, "move my flight earlier");

        var itemCaptor = org.mockito.ArgumentCaptor.forClass(
                com.team7.mobile.data.entity.ItineraryItem.class);
        verify(itineraryItemRepository).save(itemCaptor.capture());
        com.team7.mobile.data.entity.ItineraryItem savedFlight = itemCaptor.getValue();

        assertEquals(java.time.LocalDateTime.of(2026, 9, 10, 10, 0, 0), savedFlight.getStartTime());
        assertEquals(java.time.LocalDateTime.of(2026, 9, 10, 19, 40, 0), savedFlight.getEndTime());
    }

    /**
     * Companion to savedFlightItemFallsBackToDepartureAndArrivalTimeFields:
     * that test's flight Map (built with Map.of(), which can't even hold a
     * null value) only proves the fallback works when "startTime"/"endTime"
     * are entirely ABSENT - which is what a real generate_itinerary() run
     * produces, verified live. But the fix uses activity.get(...) + a null
     * check (firstNonNull), NOT Map.getOrDefault(...) - getOrDefault only
     * falls back when a key has no mapping at all, not when it's present
     * mapped to null. If the Agent's JSON ever explicitly writes
     * "startTime": null instead of omitting the key, Jackson deserializes
     * that into the key being PRESENT with a null value, and a
     * getOrDefault-based fallback would silently miss it, reproducing the
     * original bug. This test builds that exact shape via real JSON
     * deserialization (not a hand-built Map) to prove the fallback still
     * fires either way.
     */
    @Test
    void savedFlightItemFallsBackEvenWhenStartTimeKeyIsPresentButExplicitlyNull() throws Exception {
        trip.setAgentItineraryJson("{\"day1\":{\"date\":\"2026-09-10\"},\"totalCostSGD\":500.0,\"warnings\":[]}");

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> flight = mapper.readValue(
                "{\"offerId\":\"off_test123\",\"flightNumber\":\"CZ3192\","
                        + "\"startTime\":null,\"endTime\":null,"
                        + "\"departureTime\":\"2026-09-10T10:00:00\",\"arrivalTime\":\"2026-09-10T19:40:00\"}",
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        // Sanity-check the test's own setup: this must be "key present, value
        // null", not "key absent" - otherwise this test would be a duplicate
        // of the Map.of()-based one instead of covering the different case.
        org.junit.jupiter.api.Assertions.assertTrue(flight.containsKey("startTime"));
        org.junit.jupiter.api.Assertions.assertNull(flight.get("startTime"));

        Map<String, Object> updated = Map.of(
                "day1", Map.of("date", "2026-09-10", "flight", flight),
                "totalCostSGD", 500.0,
                "warnings", List.of(),
                "changeApplied", true
        );
        when(agentOrchestrator.modifyItinerary(any(), eq("move my flight earlier")))
                .thenReturn(updated);

        tripService.modifyItinerary(42L, "move my flight earlier");

        var itemCaptor = org.mockito.ArgumentCaptor.forClass(
                com.team7.mobile.data.entity.ItineraryItem.class);
        verify(itineraryItemRepository).save(itemCaptor.capture());
        com.team7.mobile.data.entity.ItineraryItem savedFlight = itemCaptor.getValue();

        assertEquals(java.time.LocalDateTime.of(2026, 9, 10, 10, 0, 0), savedFlight.getStartTime());
        assertEquals(java.time.LocalDateTime.of(2026, 9, 10, 19, 40, 0), savedFlight.getEndTime());
    }

    /**
     * Regression test for two more real bugs found the same way as the
     * flight startTime/endTime one - by checking every field saveItem()
     * reads against a real generated itinerary. Hotel activities use
     * "address" (not "location") and "pricePerNight" (not "price"/
     * "totalPrice"/"amount"), so both ItineraryItem.location and .price
     * silently persisted as null for every hotel item despite the real data
     * being right there under different key names.
     */
    @Test
    void savedHotelItemFallsBackToAddressAndPricePerNight() throws Exception {
        trip.setAgentItineraryJson("{\"day1\":{\"date\":\"2026-09-10\"},\"totalCostSGD\":500.0,\"warnings\":[]}");

        Map<String, Object> hotel = Map.of(
                "name", "CapsulePod@Aljunied",
                "address", "76A Lorong 27 Geylang",
                "pricePerNight", 50.68,
                "offerId", "hotel_offer_test"
        );
        Map<String, Object> updated = Map.of(
                "day1", Map.of("date", "2026-09-10", "hotel", hotel),
                "totalCostSGD", 500.0,
                "warnings", List.of(),
                "changeApplied", true
        );
        when(agentOrchestrator.modifyItinerary(any(), eq("find a hotel")))
                .thenReturn(updated);

        tripService.modifyItinerary(42L, "find a hotel");

        var itemCaptor = org.mockito.ArgumentCaptor.forClass(
                com.team7.mobile.data.entity.ItineraryItem.class);
        verify(itineraryItemRepository).save(itemCaptor.capture());
        com.team7.mobile.data.entity.ItineraryItem savedHotel = itemCaptor.getValue();

        assertEquals("76A Lorong 27 Geylang", savedHotel.getLocation());
        assertEquals(0, java.math.BigDecimal.valueOf(50.68).compareTo(savedHotel.getPrice()));
    }
}
