package com.team7.mobile.common.dto;

import java.math.BigDecimal;

/**
 * Booking DTO — flight or hotel booking record returned to the frontend.
 */
public class BookingDTO {

    private Long id;
    private Long tripId;
    private Long userId;
    private String type;          // FLIGHT / HOTEL
    private String bookingRef;
    private BigDecimal price;
    private String currency;
    private String status;

    public BookingDTO() {}

    public BookingDTO(Long id, Long tripId, Long userId, String type,
                      String bookingRef, BigDecimal price, String currency, String status) {
        this.id = id;
        this.tripId = tripId;
        this.userId = userId;
        this.type = type;
        this.bookingRef = bookingRef;
        this.price = price;
        this.currency = currency;
        this.status = status;
    }

    public Long getId() { return id; }
    public Long getTripId() { return tripId; }
    public Long getUserId() { return userId; }
    public String getType() { return type; }
    public String getBookingRef() { return bookingRef; }
    public BigDecimal getPrice() { return price; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
}
