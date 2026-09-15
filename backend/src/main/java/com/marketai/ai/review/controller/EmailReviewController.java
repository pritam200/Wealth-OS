package com.marketai.ai.review.controller;

import com.marketai.ai.review.dto.ReviewDecisionRequest;
import com.marketai.ai.review.entity.EmailReviewItem;
import com.marketai.ai.review.service.EmailReviewService;
import com.marketai.auth.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/review")
@RequiredArgsConstructor
public class EmailReviewController {

    private final EmailReviewService service;

    @GetMapping
    public ResponseEntity<List<EmailReviewItem>> pending(@AuthenticationPrincipal User user,
                                                         @RequestParam(defaultValue = "false") boolean all) {
        return ResponseEntity.ok(all ? service.listAll(user.getId()) : service.listPending(user.getId()));
    }

    @GetMapping("/count")
    public ResponseEntity<Map<String, Object>> count(@AuthenticationPrincipal User user) {
        Map<String, Object> body = new HashMap<>();
        body.put("pending", service.pendingCount(user.getId()));
        return ResponseEntity.ok(body);
    }

    /** ACCEPT / EDIT / REJECT. */
    @PostMapping("/{id}/decision")
    public ResponseEntity<EmailReviewItem> decide(@AuthenticationPrincipal User user,
                                                  @PathVariable Long id,
                                                  @RequestBody ReviewDecisionRequest req) {
        return ResponseEntity.ok(service.decide(user, id, req));
    }
}
