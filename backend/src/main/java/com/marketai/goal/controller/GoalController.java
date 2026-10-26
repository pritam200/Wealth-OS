package com.marketai.goal.controller;

import com.marketai.auth.entity.User;
import com.marketai.goal.dto.GoalDtos.*;
import com.marketai.goal.service.GoalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/goals")
@RequiredArgsConstructor
public class GoalController {

    private final GoalService service;

    @GetMapping
    public ResponseEntity<List<GoalResponse>> list(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(service.list(user.getId()));
    }

    @GetMapping("/link-options")
    public ResponseEntity<LinkOptions> linkOptions(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(service.linkOptions(user.getId()));
    }

    @PostMapping
    public ResponseEntity<GoalResponse> add(@AuthenticationPrincipal User user, @Valid @RequestBody GoalRequest req) {
        return ResponseEntity.ok(service.add(user.getId(), req));
    }

    @PutMapping("/{id}")
    public ResponseEntity<GoalResponse> update(@AuthenticationPrincipal User user, @PathVariable Long id, @Valid @RequestBody GoalRequest req) {
        return ResponseEntity.ok(service.update(user.getId(), id, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal User user, @PathVariable Long id) {
        service.delete(user.getId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/what-if")
    public ResponseEntity<WhatIfResponse> whatIf(@AuthenticationPrincipal User user, @PathVariable Long id, @RequestBody WhatIfRequest req) {
        return ResponseEntity.ok(service.whatIf(user.getId(), id, req));
    }
}
