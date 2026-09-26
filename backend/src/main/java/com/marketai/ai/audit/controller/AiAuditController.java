package com.marketai.ai.audit.controller;

import com.marketai.ai.audit.dto.AiAuditTrailResponse;
import com.marketai.ai.audit.service.AiAuditService;
import com.marketai.auth.entity.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ai-audit")
@RequiredArgsConstructor
@Tag(name = "AI Audit Trail", description = "Why the AI extraction pipeline made a decision on an imported record")
public class AiAuditController {

    private final AiAuditService aiAuditService;

    // referenceId is the gmailMessageId stored as sourceEmailId on the transaction/holding/FD
    // this extraction produced — see AiAuditService.getByReference for the linking convention.
    @GetMapping("/by-reference/{referenceId}")
    @Operation(summary = "Fetch the AI audit trail for a given source reference (e.g. a Gmail message id)")
    public ResponseEntity<List<AiAuditTrailResponse>> getByReference(
            @AuthenticationPrincipal User user,
            @PathVariable String referenceId) {
        return ResponseEntity.ok(aiAuditService.getByReference(user.getId(), referenceId));
    }
}
