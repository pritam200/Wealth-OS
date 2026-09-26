package com.marketai.cashflow.controller;

import com.marketai.auth.entity.User;
import com.marketai.cashflow.dto.DailyProjection;
import com.marketai.cashflow.service.CashFlowForecastService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/cashflow")
@RequiredArgsConstructor
public class CashFlowController {

    private static final int MAX_DAYS_AHEAD = 365;

    private final CashFlowForecastService service;

    @GetMapping("/forecast")
    public ResponseEntity<List<DailyProjection>> forecast(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "90") int days) {
        int daysAhead = Math.max(1, Math.min(days, MAX_DAYS_AHEAD));
        return ResponseEntity.ok(service.forecast(user.getId(), daysAhead));
    }
}
