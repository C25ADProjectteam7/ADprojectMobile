package com.team7.mobile.api.controller;

import com.team7.mobile.common.dto.ApiResponse;
import com.team7.mobile.common.dto.ExpenseDTO;
import com.team7.mobile.common.dto.TripDTO;
import com.team7.mobile.business.service.ExpenseService;
import com.team7.mobile.business.service.TripService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Company-wide endpoints for approval sync / budget review.
 * Used by the web admin portal and approver views.
 * All endpoints require MANAGER / FINANCE / ADMIN role.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final TripService tripService;
    private final ExpenseService expenseService;

    public AdminController(TripService tripService, ExpenseService expenseService) {
        this.tripService = tripService;
        this.expenseService = expenseService;
    }

    /** All trips across the company (budget approval sync). */
    @GetMapping("/trips")
    public ResponseEntity<ApiResponse<List<TripDTO>>> getAllTrips() {
        return ResponseEntity.ok(ApiResponse.success(tripService.getAllTrips()));
    }

    /** All expense claims across the company (approval sync / statistics). */
    @GetMapping("/expenses")
    public ResponseEntity<ApiResponse<List<ExpenseDTO>>> getAllExpenses() {
        return ResponseEntity.ok(ApiResponse.success(expenseService.getAllExpenses()));
    }

    /** Approve a claim. Body: { "expenseId": 1, "opinion": "approved, ok" } */
    @PostMapping("/expenses/approve")
    public ResponseEntity<ApiResponse<ExpenseDTO>> approveExpense(@RequestBody Map<String, Object> body) {
        Long id = Long.valueOf(body.get("expenseId").toString());
        String opinion = (String) body.getOrDefault("opinion", "");
        return ResponseEntity.ok(ApiResponse.success("Approved", expenseService.approveExpense(id, opinion)));
    }

    /** Reject a claim. Body: { "expenseId": 1, "opinion": "reason" } */
    @PostMapping("/expenses/reject")
    public ResponseEntity<ApiResponse<ExpenseDTO>> rejectExpense(@RequestBody Map<String, Object> body) {
        Long id = Long.valueOf(body.get("expenseId").toString());
        String opinion = (String) body.getOrDefault("opinion", "");
        return ResponseEntity.ok(ApiResponse.success("Rejected", expenseService.rejectExpense(id, opinion)));
    }

    /** Ask the employee for more information. Body: { "expenseId": 1, "opinion": "need receipt" } */
    @PostMapping("/expenses/request-info")
    public ResponseEntity<ApiResponse<ExpenseDTO>> requestInfo(@RequestBody Map<String, Object> body) {
        Long id = Long.valueOf(body.get("expenseId").toString());
        String opinion = (String) body.getOrDefault("opinion", "");
        return ResponseEntity.ok(ApiResponse.success("Info requested", expenseService.requestInfo(id, opinion)));
    }
}
