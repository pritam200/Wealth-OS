package com.marketai.scheduled.controller;

import com.marketai.auth.entity.User;
import com.marketai.scheduled.dto.RecurringInvestmentRequest;
import com.marketai.scheduled.dto.RecurringInvestmentResponse;
import com.marketai.scheduled.service.RecurringInvestmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/recurring-investments")
@RequiredArgsConstructor
public class RecurringInvestmentController {

    private final RecurringInvestmentService service;

    @GetMapping
    public ResponseEntity<List<RecurringInvestmentResponse>> list(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(service.list(user.getId()));
    }

    @PostMapping
    public ResponseEntity<RecurringInvestmentResponse> add(
            @AuthenticationPrincipal User user, @RequestBody RecurringInvestmentRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.add(user.getId(), req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal User user, @PathVariable Long id) {
        service.delete(user.getId(), id);
        return ResponseEntity.noContent().build();
    }
}
