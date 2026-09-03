package com.marketai.analyst.controller;

import com.marketai.analyst.dto.AnalystAssessment;
import com.marketai.recommendation.service.RecommendationEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/analyst")
@RequiredArgsConstructor
public class AnalystController {

    // Routed through RecommendationEngine (not AnalystService directly) so this response
    // carries the same confidenceScore/nextAction as every other consumer of the engine —
    // the whole point being that no two tabs can disagree on the same symbol's verdict.
    private final RecommendationEngine recommendationEngine;

    @GetMapping("/{symbol}")
    public ResponseEntity<AnalystAssessment> assess(@PathVariable String symbol,
                                                    @RequestParam(required = false) String name) {
        return ResponseEntity.ok(recommendationEngine.recommend(symbol, name));
    }
}
