package com.marketai.actions.controller;

import com.marketai.actions.dto.TodaysActionsResponse;
import com.marketai.actions.service.TodaysActionsService;
import com.marketai.auth.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/todays-actions")
@RequiredArgsConstructor
public class TodaysActionsController {

    private final TodaysActionsService service;

    @GetMapping
    public ResponseEntity<TodaysActionsResponse> get(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(service.build(user.getId()));
    }
}
