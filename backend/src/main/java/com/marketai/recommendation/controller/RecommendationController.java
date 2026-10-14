package com.marketai.recommendation.controller;

import com.marketai.analyst.dto.AnalystAssessment;
import com.marketai.recommendation.dto.MfRecommendationRequest;
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
