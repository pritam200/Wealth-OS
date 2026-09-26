package com.marketai.rebalancing.controller;

import com.marketai.auth.entity.User;
import com.marketai.rebalancing.dto.RebalancingSuggestionsResponse;
import com.marketai.rebalancing.service.RebalancingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rebalancing")
@RequiredArgsConstructor
@Tag(name = "Rebalancing", description = "Read-only current-allocation and concentration-risk report")
public class RebalancingController {

    private final RebalancingService rebalancingService;

    @GetMapping("/suggestions")
    @Operation(summary = "Current asset-class/sector allocation, concentration flags, and read-only "
        + "trim suggestions with real tax-lot-based tax impact — never an executed trade, never a "
        + "target-allocation model")
    public ResponseEntity<RebalancingSuggestionsResponse> suggestions(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(rebalancingService.build(user.getId()));
    }
}
