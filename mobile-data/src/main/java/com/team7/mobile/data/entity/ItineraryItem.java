package com.team7.mobile.data.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ItineraryItem entity — maps to itinerary_items table.
 * A single activity in an itinerary day: flight, hotel stay, restaurant, attraction, meeting, transport.
 */
@Entity
@Table(name = "itinerary_items")
public class ItineraryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "itinerary_id", nullable = false)
    private Itinerary itinerary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ItemType type;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    @Column(length = 300)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 500)
    private String location;

    @Column(length = 100)
    private String bookingRef;

    @Column(precision = 12, scale = 2)
    private BigDecimal price;

    // TripService.saveItem() always overwrites this with an explicit USD
    // fallback before persisting (matching Booking's own "USD" default and
    // the USD convention for Duffel/LiteAPI data), so this initializer is
    // currently dead - kept consistent with that convention rather than
    // "CNY" so it isn't a landmine for any future code path that persists
    // an ItineraryItem without going through saveItem().
    @Column(length = 3)
    private String currency = "USD";

    @Column(length = 20)
    private String status = "PENDING";

    public Long getId() { return id; }
    public Itinerary getItinerary() { return itinerary; }
    public void setItinerary(Itinerary itinerary) { this.itinerary = itinerary; }
    public ItemType getType() { return type; }
    public void setType(ItemType type) { this.type = type; }
    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getBookingRef() { return bookingRef; }
    public void setBookingRef(String bookingRef) { this.bookingRef = bookingRef; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public enum ItemType {
        FLIGHT, HOTEL, RESTAURANT, ATTRACTION, MEETING, TRANSPORT
    }
}
