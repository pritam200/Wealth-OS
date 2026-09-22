package com.marketai.planner.controller;

import com.marketai.auth.entity.User;
import com.marketai.planner.dto.PlannerDtos.*;
import com.marketai.planner.service.SinkingFundService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/planner/sinking-funds")
@RequiredArgsConstructor
public class SinkingFundController {

    private final SinkingFundService sinkingFundService;

    @GetMapping
    public ResponseEntity<List<SinkingFundResponse>> list(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(sinkingFundService.listFunds(user.getId()));
    }

    @PostMapping
    public ResponseEntity<SinkingFundResponse> add(@AuthenticationPrincipal User user, @RequestBody SinkingFundRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sinkingFundService.addFund(user.getId(), req));
    }

    @PutMapping("/{id}")
    public ResponseEntity<SinkingFundResponse> update(@AuthenticationPrincipal User user, @PathVariable Long id,
                                                       @RequestBody SinkingFundRequest req) {
        return ResponseEntity.ok(sinkingFundService.updateFund(user.getId(), id, req));
    }

    @GetMapping("/{id}/ledger")
    public ResponseEntity<SinkingFundLedgerResponse> ledger(@AuthenticationPrincipal User user, @PathVariable Long id,
                                                             @RequestParam(required = false) Integer year) {
        int y = year != null ? year : LocalDate.now().getYear();
        return ResponseEntity.ok(sinkingFundService.getLedger(user.getId(), id, y));
    }

    @PutMapping("/{id}/ledger")
    public ResponseEntity<SinkingFundLedgerResponse> upsertEntry(@AuthenticationPrincipal User user, @PathVariable Long id,
                                                                  @RequestBody SinkingFundEntryRequest req) {
        return ResponseEntity.ok(sinkingFundService.upsertEntry(user.getId(), id, req));
    }
}
