package com.marketai.actions.controller;

import com.marketai.actions.dto.ActionItemDto;
import com.marketai.actions.dto.ActionUpdateRequest;
import com.marketai.actions.service.ActionItemService;
import com.marketai.auth.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/actions")
@RequiredArgsConstructor
public class ActionItemController {

    private final ActionItemService service;

    /** What the user has already executed/skipped/snoozed — used to annotate the recomputed
     *  Today's Actions queue rather than to generate it. */
    @GetMapping
    public ResponseEntity<List<ActionItemDto>> today(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(service.listForToday(user.getId()));
    }

    /** Execute / Skip / Snooze. Upserts, because the queue is recomputed on each load and a
     *  given recommendation has no stored row until the user first acts on it. */
    @PostMapping
    public ResponseEntity<ActionItemDto> record(@AuthenticationPrincipal User user,
                                               @RequestBody ActionUpdateRequest req) {
        return ResponseEntity.ok(service.record(user, req));
    }

    @PatchMapping("/{id}/note")
    public ResponseEntity<ActionItemDto> note(@AuthenticationPrincipal User user,
                                             @PathVariable Long id,
                                             @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(service.updateNote(user, id, body.get("note")));
    }
}
