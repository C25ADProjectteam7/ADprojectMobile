package com.team7.mobile.business.service;

import com.team7.mobile.common.dto.ExpenseDTO;
import com.team7.mobile.data.entity.Expense;
import com.team7.mobile.data.entity.Trip;
import com.team7.mobile.data.entity.User;
import com.team7.mobile.data.repository.ExpenseRepository;
import com.team7.mobile.data.repository.TripRepository;
import com.team7.mobile.business.util.CurrentUser;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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

    private Expense findOwnedExpense(Long expenseId) {
        Long userId = currentUser.getId();
        return expenseRepository.findByIdAndUserId(expenseId, userId)
                .orElseThrow(() -> new RuntimeException("Expense not found: " + expenseId));
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
                expense.getSubmittedAt()
        );
    }
}
