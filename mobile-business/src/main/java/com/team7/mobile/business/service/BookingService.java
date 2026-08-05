package com.team7.mobile.business.service;

import com.team7.mobile.common.dto.BookingDTO;
import com.team7.mobile.data.entity.Booking;
import com.team7.mobile.data.entity.Trip;
import com.team7.mobile.data.entity.User;
import com.team7.mobile.data.repository.BookingRepository;
import com.team7.mobile.data.repository.TripRepository;
import com.team7.mobile.business.util.CurrentUser;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Booking management — flight/hotel bookings, cancellation, queries.
 */
@Service
public class BookingService {

    private final BookingRepository bookingRepository;
    private final TripRepository tripRepository;
    private final CurrentUser currentUser;

    public BookingService(BookingRepository bookingRepository,
                          TripRepository tripRepository,
                          CurrentUser currentUser) {
        this.bookingRepository = bookingRepository;
        this.tripRepository = tripRepository;
        this.currentUser = currentUser;
    }

    /**
     * Create a booking (flight or hotel) linked to an owned trip.
     */
    public BookingDTO createBooking(Long tripId, String type, String bookingRef,
                                    BigDecimal price, String currency) {
        User user = currentUser.get();
        if (user == null) {
            throw new IllegalStateException("User not authenticated");
        }
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("Trip not found: " + tripId));
        if (!trip.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Not authorized to book for trip: " + tripId);
        }

        Booking booking = new Booking();
        booking.setTrip(trip);
        booking.setUser(user);
        booking.setType(Booking.BookingType.valueOf(type));
        booking.setBookingRef(bookingRef);
        booking.setPrice(price);
        booking.setCurrency(currency != null ? currency : "CNY");
        booking.setStatus(Booking.BookingStatus.CONFIRMED);

        booking = bookingRepository.save(booking);
        return toDTO(booking);
    }

    /**
     * List current user's bookings.
     */
    public List<BookingDTO> getUserBookings() {
        Long userId = currentUser.getId();
        return bookingRepository.findByUserId(userId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * Get booking detail (owner only).
     */
    public BookingDTO getBookingById(Long bookingId) {
        Booking booking = findOwnedBooking(bookingId);
        return toDTO(booking);
    }

    /**
     * Cancel a booking (owner only).
     */
    public void cancelBooking(Long bookingId) {
        Booking booking = findOwnedBooking(bookingId);
        booking.setStatus(Booking.BookingStatus.CANCELLED);
        bookingRepository.save(booking);
    }

    private Booking findOwnedBooking(Long bookingId) {
        Long userId = currentUser.getId();
        return bookingRepository.findByIdAndUserId(bookingId, userId)
                .orElseThrow(() -> new RuntimeException("Booking not found: " + bookingId));
    }

    private BookingDTO toDTO(Booking booking) {
        return new BookingDTO(
                booking.getId(),
                booking.getTrip().getId(),
                booking.getUser().getId(),
                booking.getType().name(),
                booking.getBookingRef(),
                booking.getPrice(),
                booking.getCurrency(),
                booking.getStatus().name()
        );
    }
}
