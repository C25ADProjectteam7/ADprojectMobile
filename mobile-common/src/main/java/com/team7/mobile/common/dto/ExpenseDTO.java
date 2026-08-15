package com.team7.mobile.common.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Expense DTO — reimbursement claim returned to the frontend.
 */
public class ExpenseDTO {

    private Long id;
    private Long tripId;
    /** Trip title the claim belongs to (e.g. "Tokyo Business Trip"). */
    private String tripTitle;
    /** Trip destination city the claim belongs to (e.g. "Tokyo"). */
    private String tripDestination;
    private Long userId;
    private String category;      // FLIGHT / HOTEL / MEAL / TRANSPORT / OTHER
    private BigDecimal amount;
    private String currency;
    private String description;
    private String receiptUrl;
    private String status;        // SUBMITTED / APPROVED / REJECTED / NEEDS_INFO
    private LocalDateTime submittedAt;
    private LocalDateTime createdAt;
    /** Approval opinion/comment written by the approver. */
    private String approvalOpinion;
    /** Who reviewed the claim (username on mobile / email on web). */
    private String approverName;

    public ExpenseDTO() {}

    public ExpenseDTO(Long id, Long tripId, Long userId, String category,
                      BigDecimal amount, String currency, String description,
                      String receiptUrl, String status, LocalDateTime submittedAt,
                      LocalDateTime createdAt, String approvalOpinion, String approverName) {
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
        this.createdAt = createdAt;
        this.approvalOpinion = approvalOpinion;
        this.approverName = approverName;
    }

    public void setTripTitle(String tripTitle) { this.tripTitle = tripTitle; }
    public void setTripDestination(String tripDestination) { this.tripDestination = tripDestination; }

    public Long getId() { return id; }
    public Long getTripId() { return tripId; }
    public String getTripTitle() { return tripTitle; }
    public String getTripDestination() { return tripDestination; }
    public Long getUserId() { return userId; }
    public String getCategory() { return category; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getDescription() { return description; }
    public String getReceiptUrl() { return receiptUrl; }
    public String getStatus() { return status; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public String getApprovalOpinion() { return approvalOpinion; }
    public String getApproverName() { return approverName; }
}
