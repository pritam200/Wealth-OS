package com.marketai.card.controller;

import com.marketai.auth.entity.User;
import com.marketai.card.dto.CardStatementDtos.DetectedCardResponse;
import com.marketai.card.entity.CreditCard;
import com.marketai.card.entity.DetectedCard;
import com.marketai.card.repository.CreditCardRepository;
import com.marketai.card.service.DetectedCardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Cards found in email that are not saved yet: list them, add one, link it to a saved card, or dismiss it. */
@RestController
@RequestMapping("/api/cards/detected")
@RequiredArgsConstructor
public class DetectedCardController {

    private final DetectedCardService service;
    private final CreditCardRepository cardRepository;

    @GetMapping
    public ResponseEntity<List<DetectedCardResponse>> list(@AuthenticationPrincipal User user) {
        List<CreditCard> saved = cardRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        return ResponseEntity.ok(service.pendingFor(user.getId()).stream().map(d -> toDto(d, saved)).toList());
    }

    @PostMapping("/{id}/add")
    public ResponseEntity<Map<String, Object>> add(@AuthenticationPrincipal User user, @PathVariable Long id,
                                                   @RequestBody(required = false) Map<String, String> body) {
        CreditCard card = service.add(user.getId(), id, body != null ? body.get("name") : null);
        return ResponseEntity.ok(Map.of("cardId", card.getId(), "name", card.getName()));
    }

    @PostMapping("/{id}/link/{cardId}")
    public ResponseEntity<Map<String, Object>> link(@AuthenticationPrincipal User user, @PathVariable Long id,
                                                    @PathVariable Long cardId) {
        CreditCard card = service.link(user.getId(), id, cardId);
        return ResponseEntity.ok(Map.of("cardId", card.getId(), "name", card.getName()));
    }

    @PostMapping("/{id}/dismiss")
    public ResponseEntity<Void> dismiss(@AuthenticationPrincipal User user, @PathVariable Long id) {
        service.dismiss(user.getId(), id);
        return ResponseEntity.noContent().build();
    }

    private DetectedCardResponse toDto(DetectedCard d, List<CreditCard> saved) {
        String issuer = norm(d.getIssuer());
        List<Long> likely = saved.stream()
            .filter(c -> !issuer.isEmpty() && (norm(c.getIssuer()).contains(issuer) || issuer.contains(norm(c.getIssuer())))
                && !norm(c.getIssuer()).isEmpty())
            .filter(c -> c.getLastFour() == null || c.getLastFour().isBlank() || d.getLastFour() == null
                || c.getLastFour().equals(d.getLastFour()))
            .map(CreditCard::getId).toList();
        return DetectedCardResponse.builder()
            .id(d.getId()).issuer(d.getIssuer()).lastFour(d.getLastFour()).suggestedName(d.getSuggestedName())
            .seenCount(d.getSeenCount()).pendingStatements(service.pendingStatementCount(d))
            .firstSeenAt(d.getFirstSeenAt()).lastSeenAt(d.getLastSeenAt()).likelyCardIds(likely).build();
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "").replace("bank", "");
    }
}
