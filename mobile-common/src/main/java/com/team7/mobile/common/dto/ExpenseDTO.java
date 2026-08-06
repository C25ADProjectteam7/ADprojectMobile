package com.team7.mobile.common.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Expense DTO — reimbursement claim returned to the frontend.
 */
public class ExpenseDTO {

    private Long id;
    private Long tripId;
    private Long userId;
    private String category;      // FLIGHT / HOTEL / MEAL / TRANSPORT / OTHER
    private BigDecimal amount;
    private String currency;
    private String description;
    private String receiptUrl;
    private String status;        // SUBMITTED / APPROVED / REJECTED
    private LocalDateTime submittedAt;

    public ExpenseDTO() {}

    public ExpenseDTO(Long id, Long tripId, Long userId, String category,
                      BigDecimal amount, String currency, String description,
                      String receiptUrl, String status, LocalDateTime submittedAt) {
        this.id = id;
        this.tripId = tripId;
        this.userId = userId;
        this.category = category;
        this.amount = amount;
        this.currency = currency;
        this.description = description;
        this.receiptUrl = receiptUrl;
        this.status = status;
        this.submittedAt = submittedAt;
    }

    public Long getId() { return id; }
    public Long getTripId() { return tripId; }
    public Long getUserId() { return userId; }
    public String getCategory() { return category; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getDescription() { return description; }
    public String getReceiptUrl() { return receiptUrl; }
    public String getStatus() { return status; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
}
