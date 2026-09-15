package com.marketai.recommendation.controller;

import com.marketai.auth.entity.User;
import com.marketai.recommendation.dto.PortfolioContext;
import com.marketai.recommendation.service.PortfolioContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The single source of truth for net worth, asset allocation, and total invested/current/P&L
 * across stocks + mutual funds + FD/RD/EPF/other assets minus loans. Every screen that shows
 * these numbers (Dashboard, My Wealth, Stocks, Mutual Funds) must call this endpoint rather
 * than re-deriving the same totals client-side — that duplication was the direct cause of
 * different tabs showing different net-worth/P&L figures for the same underlying data.
 */
@RestController
@RequestMapping("/api/wealth")
@RequiredArgsConstructor
public class WealthController {

    private final PortfolioContextService portfolioContextService;

    @GetMapping("/summary")
    public ResponseEntity<PortfolioContext> summary(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(portfolioContextService.build(user.getId()));
    }
}
