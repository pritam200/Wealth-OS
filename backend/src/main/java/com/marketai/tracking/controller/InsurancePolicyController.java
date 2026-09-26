package com.marketai.tracking.controller;

import com.marketai.auth.entity.User;
import com.marketai.tracking.dto.InsurancePolicyRequest;
import com.marketai.tracking.dto.InsurancePolicyResponse;
import com.marketai.tracking.service.InsurancePolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/insurance")
@RequiredArgsConstructor
public class InsurancePolicyController {

    private final InsurancePolicyService insurancePolicyService;

    @GetMapping
    public ResponseEntity<List<InsurancePolicyResponse>> list(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(insurancePolicyService.listPolicies(user.getId()));
    }

    @PostMapping
    public ResponseEntity<InsurancePolicyResponse> add(@AuthenticationPrincipal User user, @Valid @RequestBody InsurancePolicyRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(insurancePolicyService.addPolicy(user.getId(), req, user));
    }

    @PutMapping("/{id}")
    public ResponseEntity<InsurancePolicyResponse> update(@AuthenticationPrincipal User user, @PathVariable Long id, @RequestBody InsurancePolicyRequest req) {
        return ResponseEntity.ok(insurancePolicyService.updatePolicy(id, user.getId(), req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal User user, @PathVariable Long id) {
        insurancePolicyService.deletePolicy(id, user.getId());
        return ResponseEntity.noContent().build();
    }
}
