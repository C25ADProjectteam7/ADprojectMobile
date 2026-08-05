package com.team7.mobile.api.controller;

import com.team7.mobile.common.dto.ExpenseDTO;
import com.team7.mobile.business.service.ExpenseService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/expenses")
public class ExpenseController {

    private final ExpenseService expenseService;

    public ExpenseController(ExpenseService expenseService) {
        this.expenseService = expenseService;
    }

    /** Submit a new expense claim for a trip */
    @PostMapping("/{tripId}")
    public ResponseEntity<ExpenseDTO> submitExpense(@PathVariable Long tripId,
                                                    @RequestBody ExpenseSubmitRequest request) {
        return ResponseEntity.ok(expenseService.submitExpense(
                tripId, request.getCategory(), request.getAmount(),
                request.getCurrency(), request.getDescription(), request.getReceiptUrl()));
    }

    /** List current user's expense claims */
    @GetMapping
    public ResponseEntity<List<ExpenseDTO>> getUserExpenses() {
        return ResponseEntity.ok(expenseService.getUserExpenses());
    }

    /** Get expense detail */
    @GetMapping("/{id}")
    public ResponseEntity<ExpenseDTO> getExpense(@PathVariable Long id) {
        return ResponseEntity.ok(expenseService.getExpenseById(id));
    }

    /** Request body for expense submission */
    public static class ExpenseSubmitRequest {
        private String category;
        private BigDecimal amount;
        private String currency;
        private String description;
        private String receiptUrl;

        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
        public String getCurrency() { return currency; }
        public void setCurrency(String currency) { this.currency = currency; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getReceiptUrl() { return receiptUrl; }
        public void setReceiptUrl(String receiptUrl) { this.receiptUrl = receiptUrl; }
    }
}
