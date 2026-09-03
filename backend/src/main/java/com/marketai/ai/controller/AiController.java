package com.marketai.ai.controller;

import com.marketai.ai.dto.AiRequest;
import com.marketai.ai.dto.AiResponse;
import com.marketai.ai.service.AiService;
import com.marketai.auth.entity.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "AI Analyst", description = "Gemini-powered market analysis and portfolio advice")
public class AiController {

    private final AiService aiService;

    @PostMapping("/analyse-stock")
    @Operation(summary = "AI analysis for a specific stock")
    public ResponseEntity<AiResponse> analyseStock(
            @Valid @RequestBody AiRequest request,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(aiService.analyseStock(request, user));
    }

    @PostMapping("/market-summary")
    @Operation(summary = "AI-generated daily market summary")
    public ResponseEntity<AiResponse> marketSummary(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(aiService.getMarketSummary(user));
    }

    @PostMapping("/portfolio-review/{portfolioId}")
    @Operation(summary = "AI portfolio review and rebalancing suggestions")
    public ResponseEntity<AiResponse> portfolioReview(
            @PathVariable Long portfolioId,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(aiService.reviewPortfolio(portfolioId, user));
    }

    @PostMapping("/chat")
    @Operation(summary = "Free-form AI chat about Indian markets")
    public ResponseEntity<AiResponse> chat(
            @Valid @RequestBody AiRequest request,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(aiService.chat(request, user));
    }
}
