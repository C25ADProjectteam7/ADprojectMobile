package com.team7.mobile.data.entity;

import jakarta.persistence.*;
import java.time.LocalDate;

/**
 * Itinerary entity — maps to itineraries table.
 * One trip has multiple itineraries (one per day); each has itinerary items.
 */
@Entity
@Table(name = "itineraries")
public class Itinerary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @Column(nullable = false)
    private Integer dayNumber;

    private LocalDate date;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(nullable = false)
    private Boolean generatedByAgent = false;

    public Long getId() { return id; }
    public Trip getTrip() { return trip; }
    public void setTrip(Trip trip) { this.trip = trip; }
    public Integer getDayNumber() { return dayNumber; }
    public void setDayNumber(Integer dayNumber) { this.dayNumber = dayNumber; }
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Boolean getGeneratedByAgent() { return generatedByAgent; }
    public void setGeneratedByAgent(Boolean generatedByAgent) { this.generatedByAgent = generatedByAgent; }
}
