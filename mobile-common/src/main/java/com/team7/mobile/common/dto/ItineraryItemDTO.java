package com.team7.mobile.common.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Itinerary item DTO — one activity in a day (flight, hotel stay, meal, attraction...).
 */
public class ItineraryItemDTO {

    private Long id;
    private String type;          // FLIGHT / HOTEL / MEAL / ATTRACTION / TRANSPORT / MEETING
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String title;
    private String description;   // raw JSON from the Agent when fields are not fully mapped
    private String location;
    private String bookingRef;
    private BigDecimal price;
    private String currency;

    public ItineraryItemDTO() {}

    public ItineraryItemDTO(Long id, String type, LocalDateTime startTime, LocalDateTime endTime,
                            String title, String description, String location,
                            String bookingRef, BigDecimal price, String currency) {
        this.id = id;
        this.type = type;
        this.startTime = startTime;
        this.endTime = endTime;
        this.title = title;
        this.description = description;
        this.location = location;
        this.bookingRef = bookingRef;
        this.price = price;
        this.currency = currency;
    }

    public Long getId() { return id; }
    public String getType() { return type; }
    public LocalDateTime getStartTime() { return startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getLocation() { return location; }
    public String getBookingRef() { return bookingRef; }
    public BigDecimal getPrice() { return price; }
    public String getCurrency() { return currency; }
}
