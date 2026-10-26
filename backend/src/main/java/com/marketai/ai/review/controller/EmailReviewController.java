package com.marketai.ai.review.controller;

import com.marketai.ai.review.dto.ReviewDecisionRequest;
import com.marketai.ai.review.entity.EmailReviewItem;
import com.marketai.ai.review.entity.ReviewRule;
import com.marketai.ai.review.service.EmailReviewService;
import com.marketai.ai.review.service.ReviewRuleService;
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
    private final ReviewRuleService ruleService;

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

    /** Each pending item's kind: items sharing one are the same question from the same sender. */
    @GetMapping("/kinds")
    public ResponseEntity<Map<Long, String>> kinds(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ruleService.kinds(user.getId()));
    }

    /**
     * ACCEPT / EDIT / REJECT. An accept or reject is remembered and applied to every other item
     * of the same kind (unless {@code rememberForSimilar} is false); the X-Also-Decided header
     * says how many that resolved.
     */
    @PostMapping("/{id}/decision")
    public ResponseEntity<EmailReviewItem> decide(@AuthenticationPrincipal User user,
                                                  @PathVariable Long id,
                                                  @RequestBody ReviewDecisionRequest req) {
        // Decided first in its own transaction, so a failure on a similar item can't undo it.
        EmailReviewItem decided = service.decide(user, id, req);
        String decision = req.getDecision() == null ? "" : req.getDecision().trim().toUpperCase();
        int also = 0;
        if (!Boolean.FALSE.equals(req.getRememberForSimilar()) && ("ACCEPT".equals(decision) || "REJECT".equals(decision))) {
            also = ruleService.remember(user, decided, decision);
        }
        return ResponseEntity.ok().header("X-Also-Decided", String.valueOf(also)).body(decided);
    }

    @GetMapping("/rules")
    public ResponseEntity<List<ReviewRule>> rules(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ruleService.list(user.getId()));
    }

    /** Stops applying a remembered decision. Items it already decided stay decided. */
    @DeleteMapping("/rules/{id}")
    public ResponseEntity<Void> forgetRule(@AuthenticationPrincipal User user, @PathVariable Long id) {
        ruleService.forget(user.getId(), id);
        return ResponseEntity.noContent().build();
    }
}
