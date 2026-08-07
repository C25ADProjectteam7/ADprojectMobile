package com.team7.mobile.business.service;

import com.team7.mobile.common.dto.ExpenseDTO;
import com.team7.mobile.common.exception.BusinessException;
import com.team7.mobile.common.exception.ForbiddenException;
import com.team7.mobile.data.entity.Expense;
import com.team7.mobile.data.entity.Trip;
import com.team7.mobile.data.entity.User;
import com.team7.mobile.data.repository.ExpenseRepository;
import com.team7.mobile.data.repository.TripRepository;
import com.team7.mobile.business.util.CurrentUser;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Expense / reimbursement management — submit claims, query, approve/reject.
 */
@Service
public class ExpenseService {

    private final ExpenseRepository expenseRepository;
    private final TripRepository tripRepository;
    private final CurrentUser currentUser;

    public ExpenseService(ExpenseRepository expenseRepository,
                          TripRepository tripRepository,
                          CurrentUser currentUser) {
        this.expenseRepository = expenseRepository;
        this.tripRepository = tripRepository;
        this.currentUser = currentUser;
    }

    /**
     * Submit a new expense claim for an owned trip.
     */
    public ExpenseDTO submitExpense(Long tripId, String category, BigDecimal amount,
                                    String currency, String description, String receiptUrl) {
        User user = currentUser.get();
        if (user == null) {
            throw new IllegalStateException("User not authenticated");
        }
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("Trip not found: " + tripId));
        if (!trip.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Not authorized to claim for trip: " + tripId);
        }

        Expense expense = new Expense();
        expense.setTrip(trip);
        expense.setUser(user);
        expense.setCategory(Expense.ExpenseCategory.valueOf(category));
        expense.setAmount(amount);
        expense.setCurrency(currency != null ? currency : "CNY");
        expense.setDescription(description);
        expense.setReceiptUrl(receiptUrl);
        expense.setStatus(Expense.ExpenseStatus.SUBMITTED);

        expense = expenseRepository.save(expense);
        return toDTO(expense);
    }

    /**
     * List current user's expense claims.
     */
    public List<ExpenseDTO> getUserExpenses() {
        Long userId = currentUser.getId();
        return expenseRepository.findByUserId(userId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * Get expense detail (owner only).
     */
    public ExpenseDTO getExpenseById(Long expenseId) {
        Expense expense = findOwnedExpense(expenseId);
        return toDTO(expense);
    }

    /**
     * All expense claims across the company (approval sync / statistics).
     * Requires MANAGER / FINANCE / ADMIN role.
     */
    public List<ExpenseDTO> getAllExpenses() {
        requireApprovalRole();
        return expenseRepository.findAll().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * Edit/correct an expense claim. Employee can only edit their own claim,
     * and only while it is still in SUBMITTED or NEEDS_INFO state (not yet approved).
     */
    public ExpenseDTO updateExpense(Long expenseId, String category, BigDecimal amount,
                                    String currency, String description) {
        Expense expense = findOwnedExpense(expenseId);
        Expense.ExpenseStatus status = expense.getStatus();
        if (status == Expense.ExpenseStatus.APPROVED || status == Expense.ExpenseStatus.REJECTED) {
            throw new BusinessException("EXPENSE_LOCKED",
                    "Cannot edit a claim that is already " + status.name(), 400);
        }
        if (category != null) expense.setCategory(Expense.ExpenseCategory.valueOf(category));
        if (amount != null) expense.setAmount(amount);
        if (currency != null) expense.setCurrency(currency);
        if (description != null) expense.setDescription(description);
        // After correction, reset to SUBMITTED so it can be re-reviewed
        expense.setStatus(Expense.ExpenseStatus.SUBMITTED);
        expense = expenseRepository.save(expense);
        return toDTO(expense);
    }

    /**
     * Approve a claim (web admin / approver). Writes the approval opinion.
     * Only claims in SUBMITTED or NEEDS_INFO state can be approved (state machine).
     */
    public ExpenseDTO approveExpense(Long expenseId, String opinion) {
        Expense expense = findApprovableExpense(expenseId);
        expense.setStatus(Expense.ExpenseStatus.APPROVED);
        expense.setApprovalOpinion(opinion);
        expense.setApproverName(currentPrincipal());
        expense.setReviewedAt(LocalDateTime.now());
        expense = expenseRepository.save(expense);
        return toDTO(expense);
    }

    /**
     * Reject a claim (web admin / approver). Writes the rejection reason.
     * Only claims in SUBMITTED or NEEDS_INFO state can be rejected (state machine).
     */
    public ExpenseDTO rejectExpense(Long expenseId, String opinion) {
        Expense expense = findApprovableExpense(expenseId);
        expense.setStatus(Expense.ExpenseStatus.REJECTED);
        expense.setApprovalOpinion(opinion);
        expense.setApproverName(currentPrincipal());
        expense.setReviewedAt(LocalDateTime.now());
        expense = expenseRepository.save(expense);
        return toDTO(expense);
    }

    /**
     * Mark a claim as needing more information (approver asks employee to supplement).
     * Only claims in SUBMITTED state can be sent back (state machine).
     */
    public ExpenseDTO requestInfo(Long expenseId, String opinion) {
        Expense expense = findApprovableExpense(expenseId);
        expense.setStatus(Expense.ExpenseStatus.NEEDS_INFO);
        expense.setApprovalOpinion(opinion);
        expense.setApproverName(currentPrincipal());
        expense.setReviewedAt(LocalDateTime.now());
        expense = expenseRepository.save(expense);
        return toDTO(expense);
    }

    /** Current principal from the JWT — username (mobile) or email (web group token). */
    private String currentPrincipal() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    }

    /**
     * Loads a claim and verifies it can still be reviewed
     * (not already APPROVED / REJECTED). Also enforces approver role.
     */
    private Expense findApprovableExpense(Long expenseId) {
        requireApprovalRole();
        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new RuntimeException("Expense not found: " + expenseId));
        Expense.ExpenseStatus status = expense.getStatus();
        if (status == Expense.ExpenseStatus.APPROVED || status == Expense.ExpenseStatus.REJECTED) {
            throw new BusinessException("EXPENSE_ALREADY_REVIEWED",
                    "Claim already " + status.name() + " — cannot change the decision", 400);
        }
        return expense;
    }

    private Expense findOwnedExpense(Long expenseId) {
        Long userId = currentUser.getId();
        return expenseRepository.findByIdAndUserId(expenseId, userId)
                .orElseThrow(() -> new RuntimeException("Expense not found: " + expenseId));
    }

    /**
     * Throws if the current principal is not an approver (MANAGER/FINANCE/ADMIN).
     * Stateless: reads the role from the JWT authorities in the SecurityContext,
     * NOT from the DB — so tokens issued by the Web group (whose users are not
     * in our user table) still work for cross-system approval calls.
     */
    private void requireApprovalRole() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new IllegalStateException("User not authenticated");
        }
        boolean allowed = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_MANAGER")
                        || a.getAuthority().equals("ROLE_FINANCE")
                        || a.getAuthority().equals("ROLE_ADMIN"));
        if (!allowed) {
            throw new ForbiddenException(
                    "Only MANAGER/FINANCE/ADMIN can perform approval actions");
        }
    }

    private ExpenseDTO toDTO(Expense expense) {
        return new ExpenseDTO(
                expense.getId(),
                expense.getTrip().getId(),
                expense.getUser().getId(),
                expense.getCategory().name(),
                expense.getAmount(),
                expense.getCurrency(),
                expense.getDescription(),
                expense.getReceiptUrl(),
                expense.getStatus().name(),
                expense.getSubmittedAt(),
                expense.getCreatedAt(),
                expense.getApprovalOpinion(),
                expense.getApproverName()
        );
    }
}
