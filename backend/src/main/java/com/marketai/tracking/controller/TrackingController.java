package com.marketai.tracking.controller;

import com.marketai.auth.entity.User;
import com.marketai.tracking.dto.*;
import com.marketai.tracking.service.TrackingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/tracking")
@RequiredArgsConstructor
public class TrackingController {

    private final TrackingService trackingService;

    /* ── Summary ── */
    @GetMapping("/summary")
    public ResponseEntity<TrackingSummaryResponse> summary(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(trackingService.getSummary(user.getId()));
    }

    /* ── Fixed Deposits ── */
    @GetMapping("/fd")
    public ResponseEntity<List<FdResponse>> listFds(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(trackingService.listFds(user.getId()));
    }

    @PostMapping("/fd")
    public ResponseEntity<FdResponse> addFd(@AuthenticationPrincipal User user, @Valid @RequestBody FdRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(trackingService.addFd(user.getId(), req, user));
    }

    @PutMapping("/fd/{id}")
    public ResponseEntity<FdResponse> updateFd(@AuthenticationPrincipal User user, @PathVariable Long id, @RequestBody FdRequest req) {
        return ResponseEntity.ok(trackingService.updateFd(id, user.getId(), req));
    }

    @DeleteMapping("/fd/{id}")
    public ResponseEntity<Void> deleteFd(@AuthenticationPrincipal User user, @PathVariable Long id) {
        trackingService.deleteFd(id, user.getId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/fd/{id}/close")
    public ResponseEntity<FdResponse> closeFd(@AuthenticationPrincipal User user,
                                              @PathVariable Long id,
                                              @RequestBody(required = false) java.util.Map<String, Object> body) {
        java.math.BigDecimal amount = null;
        if (body != null && body.containsKey("actualAmount")) {
            amount = new java.math.BigDecimal(body.get("actualAmount").toString());
        }
        return ResponseEntity.ok(trackingService.closeFd(id, user.getId(), amount));
    }

    /* ── Recurring Deposits ── */
    @GetMapping("/rd")
    public ResponseEntity<List<RdResponse>> listRds(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(trackingService.listRds(user.getId()));
    }

    @PostMapping("/rd")
    public ResponseEntity<RdResponse> addRd(@AuthenticationPrincipal User user, @Valid @RequestBody RdRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(trackingService.addRd(user.getId(), req, user));
    }

    @PutMapping("/rd/{id}")
    public ResponseEntity<RdResponse> updateRd(@AuthenticationPrincipal User user, @PathVariable Long id, @RequestBody RdRequest req) {
        return ResponseEntity.ok(trackingService.updateRd(id, user.getId(), req));
    }

    @DeleteMapping("/rd/{id}")
    public ResponseEntity<Void> deleteRd(@AuthenticationPrincipal User user, @PathVariable Long id) {
        trackingService.deleteRd(id, user.getId());
        return ResponseEntity.noContent().build();
    }

    /* ── Loans ── */
    @GetMapping("/loan")
    public ResponseEntity<List<LoanResponse>> listLoans(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(trackingService.listLoans(user.getId()));
    }

    @PostMapping("/loan")
    public ResponseEntity<LoanResponse> addLoan(@AuthenticationPrincipal User user, @Valid @RequestBody LoanRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(trackingService.addLoan(user.getId(), req, user));
    }

    @PutMapping("/loan/{id}")
    public ResponseEntity<LoanResponse> updateLoan(@AuthenticationPrincipal User user, @PathVariable Long id, @RequestBody LoanRequest req) {
        return ResponseEntity.ok(trackingService.updateLoan(id, user.getId(), req));
    }

    @DeleteMapping("/loan/{id}")
    public ResponseEntity<Void> deleteLoan(@AuthenticationPrincipal User user, @PathVariable Long id) {
        trackingService.deleteLoan(id, user.getId());
        return ResponseEntity.noContent().build();
    }

    /* ── EPF ── */
    @GetMapping("/epf")
    public ResponseEntity<List<EpfResponse>> listEpf(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(trackingService.listEpf(user.getId()));
    }

    @PostMapping("/epf")
    public ResponseEntity<EpfResponse> addEpf(@AuthenticationPrincipal User user, @RequestBody EpfRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(trackingService.addEpf(user.getId(), req, user));
    }

    @PutMapping("/epf/{id}")
    public ResponseEntity<EpfResponse> updateEpf(@AuthenticationPrincipal User user, @PathVariable Long id, @RequestBody EpfRequest req) {
        return ResponseEntity.ok(trackingService.updateEpf(id, user.getId(), req));
    }

    @DeleteMapping("/epf/{id}")
    public ResponseEntity<Void> deleteEpf(@AuthenticationPrincipal User user, @PathVariable Long id) {
        trackingService.deleteEpf(id, user.getId());
        return ResponseEntity.noContent().build();
    }

    /* ── Other Assets ── */
    @GetMapping("/other")
    public ResponseEntity<List<OtherAssetResponse>> listOther(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(trackingService.listOtherAssets(user.getId()));
    }

    @PostMapping("/other")
    public ResponseEntity<OtherAssetResponse> addOther(@AuthenticationPrincipal User user, @Valid @RequestBody OtherAssetRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(trackingService.addOtherAsset(user.getId(), req, user));
    }

    @PutMapping("/other/{id}")
    public ResponseEntity<OtherAssetResponse> updateOther(@AuthenticationPrincipal User user, @PathVariable Long id, @Valid @RequestBody OtherAssetRequest req) {
        return ResponseEntity.ok(trackingService.updateOtherAsset(id, user.getId(), req));
    }

    @DeleteMapping("/other/{id}")
    public ResponseEntity<Void> deleteOther(@AuthenticationPrincipal User user, @PathVariable Long id) {
        trackingService.deleteOtherAsset(id, user.getId());
        return ResponseEntity.noContent().build();
    }
}
