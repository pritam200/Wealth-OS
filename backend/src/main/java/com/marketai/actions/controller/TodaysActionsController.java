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
    private final com.marketai.common.cache.PageCaches caches;

    @GetMapping
    public ResponseEntity<TodaysActionsResponse> get(@AuthenticationPrincipal User user) {
        // Advisory and slow to build: the last result shows at once while a newer one is worked out.
        return ResponseEntity.ok(caches.<TodaysActionsResponse>of("todays-actions", java.time.Duration.ofMinutes(10), false)
            .get(user.getId(), () -> service.build(user.getId())));
    }
}
