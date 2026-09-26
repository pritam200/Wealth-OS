package com.marketai.advisor.controller;

import com.marketai.advisor.dto.AdvisorAskRequest;
import com.marketai.advisor.dto.AdvisorAskResponse;
import com.marketai.advisor.service.AdvisorService;
import com.marketai.auth.entity.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/advisor")
@RequiredArgsConstructor
@Tag(name = "AI Advisor Chat", description = "Conversational Q&A grounded in the user's existing net worth, expense, reminder and portfolio data")
public class AdvisorController {

    private final AdvisorService advisorService;

    @PostMapping("/ask")
    @Operation(summary = "Ask a question about your own financial data (net worth, expenses, reminders, holdings)")
    public ResponseEntity<AdvisorAskResponse> ask(
            @Valid @RequestBody AdvisorAskRequest request,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(advisorService.ask(user.getId(), request));
    }
}
