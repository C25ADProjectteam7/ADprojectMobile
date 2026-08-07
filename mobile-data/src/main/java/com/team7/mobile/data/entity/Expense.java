package com.team7.mobile.data.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Expense entity — maps to expenses table.
 * Employees submit reimbursement claims with receipt images.
 */
@Entity
@Table(name = "expenses")
public class Expense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExpenseCategory category;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(length = 3)
    private String currency = "CNY";

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 500)
    private String receiptUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExpenseStatus status = ExpenseStatus.SUBMITTED;

    private LocalDateTime submittedAt;

    private LocalDateTime reviewedAt;

    @Column(columnDefinition = "TEXT")
    private String reviewNote;

    /** Approval opinion/comment written by the approver (or web admin). */
    @Column(columnDefinition = "TEXT")
    private String approvalOpinion;

    /** Who reviewed the claim — principal from the JWT (username on mobile, email on web). */
    @Column(length = 100)
    private String approverName;

    /** Record creation time (same as submission for expenses). */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public Trip getTrip() { return trip; }
    public void setTrip(Trip trip) { this.trip = trip; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public ExpenseCategory getCategory() { return category; }
    public void setCategory(ExpenseCategory category) { this.category = category; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getReceiptUrl() { return receiptUrl; }
    public void setReceiptUrl(String receiptUrl) { this.receiptUrl = receiptUrl; }
    public ExpenseStatus getStatus() { return status; }
    public void setStatus(ExpenseStatus status) { this.status = status; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }
    public String getApprovalOpinion() { return approvalOpinion; }
    public void setApprovalOpinion(String approvalOpinion) { this.approvalOpinion = approvalOpinion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }
    public String getApproverName() { return approverName; }
    public void setApproverName(String approverName) { this.approverName = approverName; }

    public enum ExpenseCategory {
        FLIGHT, HOTEL, MEAL, TRANSPORT, OTHER
    }

    public enum ExpenseStatus {
        SUBMITTED, APPROVED, REJECTED, NEEDS_INFO
    }

    @PrePersist
    protected void onCreate() {
        if (submittedAt == null) submittedAt = LocalDateTime.now();
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
