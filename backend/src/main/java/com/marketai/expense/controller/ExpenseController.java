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
    private final com.marketai.expense.service.MerchantCategoryRuleService merchantRules;

    @GetMapping
    public ResponseEntity<List<ExpenseResponse>> listExpenses(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String q) {
        if (from != null || to != null || category != null || q != null) {
            return ResponseEntity.ok(expenseService.search(user.getId(), from, to, category, q));
        }
        if (year != null && month != null) {
            return ResponseEntity.ok(expenseService.getMonthly(user.getId(), year, month));
        }
        return ResponseEntity.ok(expenseService.listExpenses(user.getId()));
    }

    @GetMapping("/merchant-rules")
    public ResponseEntity<List<Map<String, Object>>> merchantRules(@AuthenticationPrincipal User user) {
        // Category by its label, as everywhere else in the API (the enum's name is internal).
        return ResponseEntity.ok(merchantRules.list(user.getId()).stream().map(r -> Map.<String, Object>of(
            "id", r.getId(), "merchant", r.getMerchant() != null ? r.getMerchant() : r.getMerchantKey(),
            "category", r.getCategory().getLabel(), "updatedAt", r.getUpdatedAt())).toList());
    }

    @DeleteMapping("/merchant-rules/{id}")
    public ResponseEntity<Void> forgetMerchantRule(@AuthenticationPrincipal User user, @PathVariable Long id) {
        return merchantRules.forget(user.getId(), id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping
    public ResponseEntity<ExpenseResponse> addExpense(
            @AuthenticationPrincipal User user,
            @RequestBody @jakarta.validation.Valid ExpenseRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(expenseService.addExpense(user.getId(), req));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ExpenseResponse> updateExpense(
            @AuthenticationPrincipal User user,
            @PathVariable Long id,
            @RequestBody @jakarta.validation.Valid ExpenseRequest req) {
        return ResponseEntity.ok(expenseService.updateExpense(user.getId(), id, req));
    }

    /** The planner's "move to a different section" action — see ExpenseService for why this is
     *  a dedicated endpoint rather than folded into the generic update. */
    @PutMapping("/{id}/plan-category")
    public ResponseEntity<ExpenseResponse> setPlanCategory(
            @AuthenticationPrincipal User user,
            @PathVariable Long id,
            @RequestBody com.marketai.planner.dto.PlannerDtos.MoveExpenseRequest req) {
        return ResponseEntity.ok(expenseService.setPlanCategoryOverride(user.getId(), id, req.getPlanCategoryKey()));
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
