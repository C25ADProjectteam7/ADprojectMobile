package com.team7.mobile.business.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team7.mobile.business.agent.AgentOrchestrator;
import com.team7.mobile.business.util.CurrentUser;
import com.team7.mobile.data.entity.Trip;
import com.team7.mobile.data.entity.User;
import com.team7.mobile.data.repository.AgentConversationRepository;
import com.team7.mobile.data.repository.ItineraryItemRepository;
import com.team7.mobile.data.repository.ItineraryRepository;
import com.team7.mobile.data.repository.TripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain Mockito test for TripService.bookTrip() / persistBookingLeg(): a
 * real bug found by checking persistBookingLeg()'s field reads against
 * agent-ml-service's liteapi_client.book_hotel() return shape.
 */
class TripServiceBookTripTest {

    private TripRepository tripRepository;
    private BookingService bookingService;
    private AgentOrchestrator agentOrchestrator;
    private TripService tripService;
    private Trip trip;
    private User user;

    @BeforeEach
    void setUp() throws Exception {
        tripRepository = mock(TripRepository.class);
        bookingService = mock(BookingService.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        agentOrchestrator = mock(AgentOrchestrator.class);

        tripService = new TripService(
                tripRepository,
                mock(ItineraryRepository.class),
                mock(ItineraryItemRepository.class),
                mock(AgentConversationRepository.class),
                mock(com.team7.mobile.data.repository.MobileApprovalRepository.class),
                currentUser,
                agentOrchestrator,
                bookingService,
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
    }

    private static void setId(Object entity, Long id) throws Exception {
        java.lang.reflect.Field field = entity.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    /**
     * liteapi_client.book_hotel() always sets "hotelConfirmationCode" in its
     * return dict via data.get(...), so the key is always PRESENT in the
     * JSON that reaches persistBookingLeg() - if LiteAPI just hasn't issued
     * a confirmation code yet (a real pattern for hotel bookings, not
     * hypothetical), that's "present mapped to null", not "absent". A
     * getOrDefault-based fallback would not catch that and would silently
     * persist bookingRef=null for a successfully confirmed hotel booking,
     * leaving the traveler with no reference to look up their reservation.
     * Built via real JSON deserialization (not a hand-built Map) to prove
     * this against the exact shape Jackson actually produces.
     */
    @Test
    void hotelBookingRefFallsBackToBookingIdWhenConfirmationCodeIsExplicitlyNull() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> hotelResult = mapper.readValue(
                "{\"success\":true,\"bookingId\":\"LP123456\",\"status\":\"CONFIRMED\","
                        + "\"hotelConfirmationCode\":null,\"totalPrice\":150.50,\"currency\":\"USD\"}",
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        // Sanity-check the test's own setup.
        org.junit.jupiter.api.Assertions.assertTrue(hotelResult.containsKey("hotelConfirmationCode"));
        org.junit.jupiter.api.Assertions.assertNull(hotelResult.get("hotelConfirmationCode"));

        Map<String, Object> bookResult = Map.of("hotelResult", hotelResult);
        when(agentOrchestrator.bookTrip(any())).thenReturn(bookResult);

        tripService.bookTrip(42L, Map.of("itinerary", Map.of()));

        verify(bookingService).createBooking(
                eq(42L), eq("HOTEL"), eq("LP123456"), any(), eq("USD"));
    }
}
