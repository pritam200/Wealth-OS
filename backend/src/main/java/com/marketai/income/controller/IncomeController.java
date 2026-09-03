package com.marketai.income.controller;

import com.marketai.auth.entity.User;
import com.marketai.income.dto.IncomeRequest;
import com.marketai.income.dto.IncomeResponse;
import com.marketai.income.service.IncomeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/income")
@RequiredArgsConstructor
public class IncomeController {

    private final IncomeService service;

    @GetMapping
    public ResponseEntity<List<IncomeResponse>> list(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        java.time.LocalDate now = java.time.LocalDate.now();
        return ResponseEntity.ok(service.list(user.getId(),
            year != null ? year : now.getYear(),
            month != null ? month : now.getMonthValue()));
    }

    @GetMapping("/by-source")
    public ResponseEntity<List<IncomeResponse>> bySource(
            @AuthenticationPrincipal User user,
            @RequestParam String source,
            @RequestParam(required = false) Integer year) {
        int y = year != null ? year : java.time.LocalDate.now().getYear();
        return ResponseEntity.ok(service.listBySource(user.getId(), source, y));
    }

    @PostMapping
    public ResponseEntity<IncomeResponse> add(@AuthenticationPrincipal User user,
                                              @RequestBody IncomeRequest req) {
        return ResponseEntity.ok(service.add(user.getId(), req));
    }

    @PutMapping("/{id}")
    public ResponseEntity<IncomeResponse> update(@AuthenticationPrincipal User user, @PathVariable Long id, @RequestBody IncomeRequest req) {
        return ResponseEntity.ok(service.update(user.getId(), id, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal User user, @PathVariable Long id) {
        service.delete(user.getId(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        java.time.LocalDate now = java.time.LocalDate.now();
        return ResponseEntity.ok(service.getMonthlySummary(user.getId(),
            year != null ? year : now.getYear(),
            month != null ? month : now.getMonthValue()));
    }
}
