package com.team7.mobile.api.controller;

import com.team7.mobile.common.dto.ApiResponse;
import com.team7.mobile.common.dto.ExpenseDTO;
import com.team7.mobile.business.service.ExpenseService;
import com.team7.mobile.business.service.FileStorageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/expenses")
public class ExpenseController {

    private final ExpenseService expenseService;
    private final FileStorageService fileStorageService;

    public ExpenseController(ExpenseService expenseService, FileStorageService fileStorageService) {
        this.expenseService = expenseService;
        this.fileStorageService = fileStorageService;
    }

    /**
     * Upload a receipt image (multipart form-data) and create an expense record.
     * Fields: file (required), tripId, category, amount, currency, description.
     * The image is stored on the server; receiptUrl points to it so the web admin
     * can view it via GET /uploads/**.
     */
    @PostMapping("/upload-receipt")
    public ResponseEntity<ApiResponse<ExpenseDTO>> uploadReceipt(
            @RequestParam("file") MultipartFile file,
            @RequestParam("tripId") Long tripId,
            @RequestParam("category") String category,
            @RequestParam("amount") BigDecimal amount,
            @RequestParam(value = "currency", required = false) String currency,
            @RequestParam(value = "description", required = false) String description) {

        String receiptUrl = fileStorageService.storeReceipt(file);
        ExpenseDTO expense = expenseService.submitExpense(
                tripId, category, amount, currency, description, receiptUrl);
        return ResponseEntity.ok(ApiResponse.success("Receipt uploaded", expense));
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
