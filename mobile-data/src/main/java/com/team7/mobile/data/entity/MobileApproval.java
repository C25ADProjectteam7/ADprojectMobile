package com.team7.mobile.data.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Read-only view of the SHARED approvals table - the web Manager approval
 * workflow (ADProjectWeb) writes this table in the same mobile database.
 * The mobile app reads the decision + note here to show "Rejected" with the
 * manager's reason on the trip detail page.
 */
@Entity
@Table(name = "approvals")
public class MobileApproval {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "mobile_trip_id", nullable = false)
    private Long mobileTripId;

    private String tripTitle;
    private String employeeName;
    private String department;
    private String destination;
    private LocalDate startDate;
    private LocalDate endDate;
    private BigDecimal budgetRequested;
    private BigDecimal departmentBudgetLimit;

    @Column(nullable = false)
    private String status;

    /** Manager's approval/rejection note - shown to the employee. */
    private String note;

    @Column(nullable = false)
    private LocalDateTime submittedAt;

    private LocalDateTime decidedAt;

    public MobileApproval() {}

    public Long getId() { return id; }
    public Long getMobileTripId() { return mobileTripId; }
    public String getTripTitle() { return tripTitle; }
    public String getEmployeeName() { return employeeName; }
    public String getDepartment() { return department; }
    public String getDestination() { return destination; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public BigDecimal getBudgetRequested() { return budgetRequested; }
    public BigDecimal getDepartmentBudgetLimit() { return departmentBudgetLimit; }
    public String getStatus() { return status; }
    public String getNote() { return note; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
}
