package com.marketai.redemption.controller;

import com.marketai.auth.entity.User;
import com.marketai.redemption.dto.DeploymentPlan;
import com.marketai.redemption.dto.ReinvestmentRequest;
import com.marketai.redemption.entity.MfRedemption;
import com.marketai.redemption.service.RedemptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/redemptions")
@RequiredArgsConstructor
public class RedemptionController {

    private final RedemptionService redemptionService;

    @GetMapping
    public ResponseEntity<List<MfRedemption>> list(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(redemptionService.list(user.getId()));
    }

    @GetMapping("/{id}/deployment-plan")
    public ResponseEntity<DeploymentPlan> getDeploymentPlan(@AuthenticationPrincipal User user, @PathVariable Long id) {
        return ResponseEntity.ok(redemptionService.getDeploymentPlan(user.getId(), id));
    }

    @PostMapping("/{id}/reinvestments")
    public ResponseEntity<MfRedemption> recordReinvestment(
            @AuthenticationPrincipal User user, @PathVariable Long id, @RequestBody ReinvestmentRequest req) {
        return ResponseEntity.ok(redemptionService.recordReinvestment(
            user.getId(), id, req.getAmount(), req.getDate(), req.getTargetFund(), req.getNote()));
    }
}
