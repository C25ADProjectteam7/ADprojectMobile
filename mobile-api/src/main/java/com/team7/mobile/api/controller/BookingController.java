package com.team7.mobile.api.controller;

import com.team7.mobile.common.dto.BookingDTO;
import com.team7.mobile.business.service.BookingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    /** Create a booking for a trip */
    @PostMapping("/{tripId}")
    public ResponseEntity<BookingDTO> createBooking(@PathVariable Long tripId,
                                                    @RequestBody Map<String, Object> body) {
        String type = (String) body.get("type");
        String bookingRef = (String) body.get("bookingRef");
        BigDecimal price = body.get("price") != null
                ? new BigDecimal(body.get("price").toString()) : null;
        String currency = (String) body.get("currency");
        return ResponseEntity.ok(bookingService.createBooking(tripId, type, bookingRef, price, currency));
    }

    /** List current user's bookings */
    @GetMapping
    public ResponseEntity<List<BookingDTO>> getUserBookings() {
        return ResponseEntity.ok(bookingService.getUserBookings());
    }

    /** Get booking detail */
    @GetMapping("/{id}")
    public ResponseEntity<BookingDTO> getBooking(@PathVariable Long id) {
        return ResponseEntity.ok(bookingService.getBookingById(id));
    }

    /** Cancel booking */
    @PutMapping("/{id}/cancel")
    public ResponseEntity<Map<String, String>> cancelBooking(@PathVariable Long id) {
        bookingService.cancelBooking(id);
        return ResponseEntity.ok(Map.of("message", "Booking cancelled"));
    }
}
