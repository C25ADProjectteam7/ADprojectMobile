package com.team7.mobile.common.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Trip response DTO — returned to the mobile frontend.
 */
public class TripDTO {

    private Long id;
    private Long userId;
    private String title;
    private String destination;
    private LocalDate startDate;
    private LocalDate endDate;
    private BigDecimal budgetTotal;
    private String status;

    public TripDTO() {}

    public TripDTO(Long id, Long userId, String title, String destination,
                   LocalDate startDate, LocalDate endDate,
                   BigDecimal budgetTotal, String status) {
        this.id = id;
        this.userId = userId;
        this.title = title;
        this.destination = destination;
        this.startDate = startDate;
        this.endDate = endDate;
        this.budgetTotal = budgetTotal;
        this.status = status;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getTitle() { return title; }
    public String getDestination() { return destination; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public BigDecimal getBudgetTotal() { return budgetTotal; }
    public String getStatus() { return status; }
}
