package com.marketai.expense.controller;

import com.marketai.auth.entity.User;
import com.marketai.expense.dto.ExpenseRequest;
import com.marketai.expense.dto.ExpenseResponse;
import com.marketai.expense.service.ExpenseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/expenses")
@RequiredArgsConstructor
public class ExpenseController {

    private final ExpenseService expenseService;

    @GetMapping
    public ResponseEntity<List<ExpenseResponse>> listExpenses(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        if (year != null && month != null) {
            return ResponseEntity.ok(expenseService.getMonthly(user.getId(), year, month));
        }
        return ResponseEntity.ok(expenseService.listExpenses(user.getId()));
    }

    @PostMapping
    public ResponseEntity<ExpenseResponse> addExpense(
            @AuthenticationPrincipal User user,
            @RequestBody ExpenseRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(expenseService.addExpense(user.getId(), req));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ExpenseResponse> updateExpense(
            @AuthenticationPrincipal User user,
            @PathVariable Long id,
            @RequestBody ExpenseRequest req) {
        return ResponseEntity.ok(expenseService.updateExpense(user.getId(), id, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteExpense(
            @AuthenticationPrincipal User user,
            @PathVariable Long id) {
        expenseService.deleteExpense(user.getId(), id);
        return ResponseEntity.noContent().build();
    }

    // One-time cleanup for rows imported before the fix that stops Investment-flavoured
    // debits from ever being booked as an Expense (see BankTransactionParser).
    @GetMapping("/miscategorized-investments")
    public ResponseEntity<List<ExpenseResponse>> listMiscategorizedInvestments(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(expenseService.listMiscategorizedInvestments(user.getId()));
    }

    @DeleteMapping("/miscategorized-investments")
    public ResponseEntity<Map<String, Integer>> purgeMiscategorizedInvestments(@AuthenticationPrincipal User user) {
        int removed = expenseService.purgeMiscategorizedInvestments(user.getId());
        return ResponseEntity.ok(Collections.singletonMap("removed", removed));
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getMonthlySummary(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        int y = year != null ? year : LocalDate.now().getYear();
        int m = month != null ? month : LocalDate.now().getMonthValue();
        return ResponseEntity.ok(expenseService.getMonthlySummary(user.getId(), y, m));
    }
}
