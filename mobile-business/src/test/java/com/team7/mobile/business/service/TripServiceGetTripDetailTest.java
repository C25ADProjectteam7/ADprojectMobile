package com.team7.mobile.business.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team7.mobile.business.agent.AgentOrchestrator;
import com.team7.mobile.business.util.CurrentUser;
import com.team7.mobile.common.dto.TripDetailDTO;
import com.team7.mobile.data.entity.Itinerary;
import com.team7.mobile.data.entity.ItineraryItem;
import com.team7.mobile.data.entity.Trip;
import com.team7.mobile.data.entity.User;
import com.team7.mobile.data.repository.AgentConversationRepository;
import com.team7.mobile.data.repository.ItineraryItemRepository;
import com.team7.mobile.data.repository.ItineraryRepository;
import com.team7.mobile.data.repository.TripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for a real N+1 query fix: TripService.getTripDetail() used
 * to call itineraryItemRepository.findByItineraryId(id) once per day in a
 * loop. Now it batch-fetches all items for the trip via findByItineraryIdIn()
 * and groups them in memory - this test proves both that the grouping is
 * still correct per-day, and that the old per-day query method is no longer
 * called at all.
 */
class TripServiceGetTripDetailTest {

    private TripRepository tripRepository;
    private ItineraryRepository itineraryRepository;
    private ItineraryItemRepository itineraryItemRepository;
    private CurrentUser currentUser;
    private TripService tripService;
    private Trip trip;

    @BeforeEach
    void setUp() throws Exception {
        tripRepository = mock(TripRepository.class);
        itineraryRepository = mock(ItineraryRepository.class);
        itineraryItemRepository = mock(ItineraryItemRepository.class);
        currentUser = mock(CurrentUser.class);

        tripService = new TripService(
                tripRepository,
                itineraryRepository,
                itineraryItemRepository,
                mock(AgentConversationRepository.class),
                mock(com.team7.mobile.data.repository.MobileApprovalRepository.class),
                currentUser,
                mock(AgentOrchestrator.class),
                mock(BookingService.class),
                new ObjectMapper()
        );

        User user = new User();
        setId(user, 1L);

        trip = new Trip();
        setId(trip, 42L);
        trip.setUser(user);
        trip.setStartDate(LocalDate.of(2026, 9, 10));
        trip.setEndDate(LocalDate.of(2026, 9, 11));

        when(currentUser.getId()).thenReturn(1L);
        when(tripRepository.findById(42L)).thenReturn(Optional.of(trip));
    }

    private static void setId(Object entity, Long id) throws Exception {
        java.lang.reflect.Field field = entity.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    private static Itinerary itineraryWithId(Long id, int dayNumber, LocalDate date) throws Exception {
        Itinerary itinerary = new Itinerary();
        setId(itinerary, id);
        itinerary.setDayNumber(dayNumber);
        itinerary.setDate(date);
        itinerary.setGeneratedByAgent(true);
        return itinerary;
    }

    private static ItineraryItem itemFor(Itinerary itinerary, Long id, String title) throws Exception {
        ItineraryItem item = new ItineraryItem();
        setId(item, id);
        item.setItinerary(itinerary);
        item.setType(ItineraryItem.ItemType.FLIGHT);
        item.setTitle(title);
        return item;
    }

    @Test
    void batchFetchesAndGroupsItemsCorrectlyPerDayWithoutPerDayQueries() throws Exception {
        Itinerary day1 = itineraryWithId(100L, 1, LocalDate.of(2026, 9, 10));
        Itinerary day2 = itineraryWithId(101L, 2, LocalDate.of(2026, 9, 11));
        when(itineraryRepository.findByTripIdOrderByDayNumber(42L)).thenReturn(List.of(day1, day2));

        ItineraryItem day1Flight = itemFor(day1, 1000L, "Day 1 Flight");
        ItineraryItem day2Hotel = itemFor(day2, 1001L, "Day 2 Hotel");
        ItineraryItem day2Dinner = itemFor(day2, 1002L, "Day 2 Dinner");
        // Deliberately returned in a mixed/interleaved order (not grouped by
        // itinerary already) to prove the grouping logic - not just
        // concatenation - is what puts each item under the right day.
        when(itineraryItemRepository.findByItineraryIdIn(List.of(100L, 101L)))
                .thenReturn(List.of(day2Hotel, day1Flight, day2Dinner));

        TripDetailDTO detail = tripService.getTripDetail(42L);

        assertEquals(2, detail.getItineraries().size());
        assertEquals(1, detail.getItineraries().get(0).getItems().size());
        assertEquals("Day 1 Flight", detail.getItineraries().get(0).getItems().get(0).getTitle());
        assertEquals(2, detail.getItineraries().get(1).getItems().size());
        assertTrue(detail.getItineraries().get(1).getItems().stream()
                .anyMatch(i -> "Day 2 Hotel".equals(i.getTitle())));
        assertTrue(detail.getItineraries().get(1).getItems().stream()
                .anyMatch(i -> "Day 2 Dinner".equals(i.getTitle())));

        // The whole point of the fix: no more one findByItineraryId() call per day.
        verify(itineraryItemRepository, never()).findByItineraryId(anyLong());
        verify(itineraryItemRepository).findByItineraryIdIn(eq(List.of(100L, 101L)));
    }

    @Test
    void tripWithNoItinerariesYetReturnsEmptyListWithoutError() {
        when(itineraryRepository.findByTripIdOrderByDayNumber(42L)).thenReturn(List.of());
        when(itineraryItemRepository.findByItineraryIdIn(any())).thenReturn(List.of());

        TripDetailDTO detail = tripService.getTripDetail(42L);

        assertTrue(detail.getItineraries().isEmpty());
    }
}
