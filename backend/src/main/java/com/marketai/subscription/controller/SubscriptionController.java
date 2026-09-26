package com.marketai.subscription.controller;

import com.marketai.auth.entity.User;
import com.marketai.subscription.dto.SubscriptionResponse;
import com.marketai.subscription.service.SubscriptionDetectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/subscriptions")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionDetectionService service;

    @GetMapping
    public ResponseEntity<List<SubscriptionResponse>> list(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(service.detectSubscriptions(user.getId()));
    }
}
