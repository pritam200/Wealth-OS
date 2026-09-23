package com.marketai.investmentplan.controller;

import com.marketai.auth.entity.User;
import com.marketai.investmentplan.dto.MonthlyPlanReviewResponse;
import com.marketai.investmentplan.dto.PlannedInvestmentRequest;
import com.marketai.investmentplan.dto.PlannedInvestmentResponse;
import com.marketai.investmentplan.service.PlannedInvestmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@RestController
@RequestMapping("/api/investment-plan")
@RequiredArgsConstructor
public class PlannedInvestmentController {

    private final PlannedInvestmentService service;

    private LocalDate parseMonth(String month) {
        // Accepts "yyyy-MM" (e.g. "2026-09") — the day is irrelevant, everything is
        // normalized to the first of the month in the service layer.
        return LocalDate.parse(month + "-01", DateTimeFormatter.ISO_LOCAL_DATE);
    }

    @GetMapping("/{month}")
    public ResponseEntity<List<PlannedInvestmentResponse>> listPlan(
            @AuthenticationPrincipal User user, @PathVariable String month) {
        return ResponseEntity.ok(service.listPlan(user.getId(), parseMonth(month)));
    }

    @PostMapping("/{month}")
    public ResponseEntity<PlannedInvestmentResponse> addPlan(
            @AuthenticationPrincipal User user, @PathVariable String month,
            @RequestBody @jakarta.validation.Valid PlannedInvestmentRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addPlan(user.getId(), parseMonth(month), req));
    }

    @DeleteMapping("/{month}/{id}")
    public ResponseEntity<Void> deletePlan(
            @AuthenticationPrincipal User user, @PathVariable String month, @PathVariable Long id) {
        service.deletePlan(user.getId(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{month}/review")
    public ResponseEntity<MonthlyPlanReviewResponse> review(
            @AuthenticationPrincipal User user, @PathVariable String month) {
        return ResponseEntity.ok(service.getReview(user.getId(), parseMonth(month)));
    }
}
