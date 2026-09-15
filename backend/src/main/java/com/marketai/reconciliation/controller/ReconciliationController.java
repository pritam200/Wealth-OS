package com.marketai.reconciliation.controller;

import com.marketai.auth.entity.User;
import com.marketai.reconciliation.dto.ReconciliationReportDto;
import com.marketai.reconciliation.service.ReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reconciliation")
@RequiredArgsConstructor
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    @GetMapping("/report")
    public ResponseEntity<ReconciliationReportDto> report(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(reconciliationService.checkAll(user.getId()));
    }
}
