package com.marketai.card.controller;

import com.marketai.auth.entity.User;
import com.marketai.card.dto.CardDtos.*;
import com.marketai.card.service.CardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/cards")
@RequiredArgsConstructor
public class CardController {

    private final CardService service;

    @GetMapping("/catalog")
    public ResponseEntity<List<CatalogEntry>> catalog() {
        return ResponseEntity.ok(service.catalog());
    }

    @GetMapping("/categories")
    public ResponseEntity<List<String>> categories() {
        return ResponseEntity.ok(service.categories());
    }

    @GetMapping
    public ResponseEntity<List<CardResponse>> list(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(service.list(user.getId()));
    }

    @PostMapping
    public ResponseEntity<CardResponse> add(@AuthenticationPrincipal User user, @RequestBody CardRequest req) {
        return ResponseEntity.ok(service.add(user.getId(), req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal User user, @PathVariable Long id) {
        service.delete(user.getId(), id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/points")
    public ResponseEntity<CardResponse> updatePoints(@AuthenticationPrincipal User user,
                                                     @PathVariable Long id,
                                                     @RequestBody Map<String, Object> body) {
        Integer points = Integer.valueOf(body.get("pointsBalance").toString());
        return ResponseEntity.ok(service.updatePoints(user.getId(), id, points));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CardResponse> update(@AuthenticationPrincipal User user,
                                               @PathVariable Long id,
                                               @RequestBody CardRequest req) {
        return ResponseEntity.ok(service.updateCard(user.getId(), id, req));
    }

    @PostMapping("/recommend")
    public ResponseEntity<List<RecommendResult>> recommend(@AuthenticationPrincipal User user,
                                                          @RequestBody RecommendRequest req) {
        return ResponseEntity.ok(service.recommend(user.getId(), req));
    }

    @GetMapping("/points-tips")
    public ResponseEntity<List<PointsTip>> pointsTips(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(service.pointsTips(user.getId()));
    }
}
