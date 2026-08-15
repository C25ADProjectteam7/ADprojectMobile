package com.team7.mobile.common.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Trip detail DTO — trip info plus full day-by-day itinerary.
 */
public class TripDetailDTO {

    private Long id;
    private String title;
    private String destination;
    private LocalDate startDate;
    private LocalDate endDate;
    private BigDecimal budgetTotal;
    private String status;
    /** Manager's rejection reason (from the shared approvals table in the
     * mobile database, written by the web approval workflow). */
    private String approvalNote;
    private List<ItineraryDTO> itineraries;

    public TripDetailDTO() {}

    public TripDetailDTO(Long id, String title, String destination,
                         LocalDate startDate, LocalDate endDate,
                         BigDecimal budgetTotal, String status,
                         String approvalNote,
                         List<ItineraryDTO> itineraries) {
        this.id = id;
        this.title = title;
        this.destination = destination;
        this.startDate = startDate;
        this.endDate = endDate;
        this.budgetTotal = budgetTotal;
        this.status = status;
        this.approvalNote = approvalNote;
        this.itineraries = itineraries;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getDestination() { return destination; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public BigDecimal getBudgetTotal() { return budgetTotal; }
    public String getStatus() { return status; }
    public String getApprovalNote() { return approvalNote; }
    public List<ItineraryDTO> getItineraries() { return itineraries; }
}
