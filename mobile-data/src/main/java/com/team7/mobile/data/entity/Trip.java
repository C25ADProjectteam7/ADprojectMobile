package com.team7.mobile.data.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Trip entity — maps to trips table.
 * A user can have multiple trips; each trip has itineraries, bookings and
 * expenses.
 */
@Entity
@Table(name = "trips")
public class Trip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 100)
    private String originCity;

    @Column(length = 100)
    private String destination;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Column(precision = 12, scale = 2)
    private BigDecimal budgetTotal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TripStatus status = TripStatus.DRAFT;

    /**
     * Raw JSON of the last itinerary the Agent produced (generate or modify) -
     * the Itinerary/ItineraryItem rows are a flattened, lossy projection of
     * this (they drop totalCost/totalCostSGD/warnings/maxHotelCommuteMinutes/
     * generatedAt), so a later "modify this itinerary" request needs this
     * full blob to hand back to modify_itinerary as its currentItinerary input.
     */
    @Column(columnDefinition = "TEXT")
    private String agentItineraryJson;

    /**
     * Running compressed summary of agent_conversations turns older than the
     * most recent window sent to the Agent verbatim - without this, facts
     * established many turns ago (destination, budget, ...) would simply be
     * lost once they age out of that window instead of being carried
     * forward. Updated incrementally (existing summary + only the
     * newly-dropped turns), not recomputed from scratch each time.
     */
    @Column(columnDefinition = "TEXT")
    private String conversationSummary;

    /** How many of the oldest turns (by count) are already folded into conversationSummary. */
    private Integer conversationSummarizedThroughCount;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getOriginCity() {
        return originCity;
    }

    public void setOriginCity(String originCity) {
        this.originCity = originCity;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public BigDecimal getBudgetTotal() {
        return budgetTotal;
    }

    public void setBudgetTotal(BigDecimal budgetTotal) {
        this.budgetTotal = budgetTotal;
    }

    public TripStatus getStatus() {
        return status;
    }

    public void setStatus(TripStatus status) {
        this.status = status;
    }

    public String getAgentItineraryJson() {
        return agentItineraryJson;
    }

    public void setAgentItineraryJson(String agentItineraryJson) {
        this.agentItineraryJson = agentItineraryJson;
    }

    public String getConversationSummary() {
        return conversationSummary;
    }

    public void setConversationSummary(String conversationSummary) {
        this.conversationSummary = conversationSummary;
    }

    public Integer getConversationSummarizedThroughCount() {
        return conversationSummarizedThroughCount;
    }

    public void setConversationSummarizedThroughCount(Integer conversationSummarizedThroughCount) {
        this.conversationSummarizedThroughCount = conversationSummarizedThroughCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public enum TripStatus {
        DRAFT, PLANNED, APPROVED, IN_PROGRESS, COMPLETED, CANCELLED
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
