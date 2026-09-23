package com.marketai.scheduled.controller;

import com.marketai.auth.entity.User;
import com.marketai.scheduled.dto.ScheduledInvestmentSummary;
import com.marketai.scheduled.service.ScheduledInvestmentsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/scheduled-investments")
@RequiredArgsConstructor
public class ScheduledInvestmentsController {

    private final ScheduledInvestmentsService service;

    @GetMapping
    public ResponseEntity<List<ScheduledInvestmentSummary>> listAll(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(service.listAll(user.getId()));
    }
}
