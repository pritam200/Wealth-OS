package com.marketai.recommendation.controller;

import com.marketai.analyst.dto.AnalystAssessment;
import com.marketai.recommendation.dto.MfRecommendationRequest;
import com.marketai.recommendation.service.InvestPlanService;
import com.marketai.recommendation.service.RecommendationEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/recommendation")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationEngine engine;
    private final com.marketai.recommendation.service.SipDetector sipDetector;
    private final InvestPlanService investPlans;

    // Real trailing index returns, replacing the hardcoded benchmark strings that used
    // to live in the Tab6 Mutual Funds UI.
    @GetMapping("/benchmarks")
    public ResponseEntity<java.util.Map<String, Double>> benchmarks() {
        return ResponseEntity.ok(engine.getIndexBenchmarks());
    }

    @GetMapping("/{symbol}")
    public ResponseEntity<AnalystAssessment> get(
            @PathVariable String symbol,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Double pnlPercent,
            @RequestParam(required = false) Double holdingValue,
            @RequestParam(required = false) Double totalPortfolioValue) {
        return ResponseEntity.ok(engine.recommend(symbol, name, pnlPercent, holdingValue, totalPortfolioValue));
    }

    /** Where a sum of savings could go, and in what proportion — rule-based, not advice. */
    @PostMapping("/invest-plan")
    public ResponseEntity<?> investPlan(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.marketai.auth.entity.User user,
            @RequestBody InvestPlanRequest req) {
        if (req.amount() == null || req.amount().signum() <= 0 || req.amount().compareTo(new java.math.BigDecimal("1000000000")) > 0)
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "Enter an amount between ₹1 and ₹100 crore."));
        int years = req.horizonYears() == null ? 5 : Math.max(1, Math.min(40, req.horizonYears()));
        var risk = req.risk() == null ? InvestPlanService.Risk.MODERATE : req.risk();
        return ResponseEntity.ok(investPlans.suggest(user.getId(), req.amount(), years, risk));
    }

    public record InvestPlanRequest(java.math.BigDecimal amount, Integer horizonYears, InvestPlanService.Risk risk) {}

    // Mutual funds need more holding-specific numbers (buyDate/XIRR/portfolio concentration)
    // than a GET can carry cleanly — same engine, dedicated MF-aware entry point.
    @PostMapping("/mf")
    public ResponseEntity<AnalystAssessment> getMf(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.marketai.auth.entity.User user,
            @RequestBody MfRecommendationRequest req) {
        // SIP or lump sum comes from the user's own records, never from the request body.
        var sip = sipDetector.detect(user != null ? user.getId() : null, req.getSymbol());
        req.setSipStatus(sip.status());
        req.setSipAmount(sip.amount());
        return ResponseEntity.ok(engine.recommendMf(req));
    }
}
