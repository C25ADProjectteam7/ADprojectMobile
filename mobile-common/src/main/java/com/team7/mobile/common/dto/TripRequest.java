package com.team7.mobile.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Trip creation request — user input for creating a new business trip.
 */
public class TripRequest {

    @NotBlank
    private String title;

    @NotBlank
    private String destination;

    @NotNull
    private LocalDate startDate;

    @NotNull
    private LocalDate endDate;

    @Positive
    private BigDecimal budgetTotal;

    /** Free-text preferences passed to the Agent (e.g. "close to downtown", "direct flight") */
    private List<String> preferences;

    public TripRequest() {}

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public BigDecimal getBudgetTotal() { return budgetTotal; }
    public void setBudgetTotal(BigDecimal budgetTotal) { this.budgetTotal = budgetTotal; }
    public List<String> getPreferences() { return preferences; }
    public void setPreferences(List<String> preferences) { this.preferences = preferences; }
}
