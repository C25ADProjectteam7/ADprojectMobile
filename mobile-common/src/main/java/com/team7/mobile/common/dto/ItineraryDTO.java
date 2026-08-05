package com.team7.mobile.common.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Itinerary DTO — one day of a trip with its activities.
 */
public class ItineraryDTO {

    private Long id;
    private Integer dayNumber;
    private LocalDate date;
    private Boolean generatedByAgent;
    private List<ItineraryItemDTO> items;

    public ItineraryDTO() {}

    public ItineraryDTO(Long id, Integer dayNumber, LocalDate date,
                        Boolean generatedByAgent, List<ItineraryItemDTO> items) {
        this.id = id;
        this.dayNumber = dayNumber;
        this.date = date;
        this.generatedByAgent = generatedByAgent;
        this.items = items;
    }

    public Long getId() { return id; }
    public Integer getDayNumber() { return dayNumber; }
    public LocalDate getDate() { return date; }
    public Boolean getGeneratedByAgent() { return generatedByAgent; }
    public List<ItineraryItemDTO> getItems() { return items; }
}
