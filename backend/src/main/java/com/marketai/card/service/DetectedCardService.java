package com.marketai.card.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketai.card.entity.CreditCard;
import com.marketai.card.entity.DetectedCard;
import com.marketai.card.repository.CreditCardRepository;
import com.marketai.card.repository.DetectedCardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Cards seen in email that match no saved card. A statement for such a card used to be dropped
 * with only a log line; it is now kept against a {@link DetectedCard}, the card page asks the user
 * to add or link it, and the kept statements are filed onto the card when they do.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DetectedCardService {

    private final DetectedCardRepository detectedRepo;
    private final CreditCardRepository cardRepo;
    private final CardStatementIngestService ingest;
    private final ObjectMapper objectMapper;

    /** A statement waiting for its card. */
    public record PendingStatement(String gmailMessageId, CardStatementFacts facts) {}

    /** Keeps a statement whose card isn't saved, under the detected card it belongs to. */
    @Transactional
    public void holdStatement(Long userId, CardStatementFacts f, String gmailMessageId) {
        DetectedCard d = findOrCreate(userId, f.issuer(), f.cardLast4());
        if (d == null) return; // the user said this card is not theirs
        List<PendingStatement> pending = pending(d);
        boolean known = pending.stream().anyMatch(p -> Objects.equals(p.gmailMessageId(), gmailMessageId));
        if (!known) {
            pending.add(new PendingStatement(gmailMessageId, f));
            d.setPendingStatementsJson(write(pending));
        }
        detectedRepo.save(d);
        log.info("Card statement {} kept for detected card {} ({} ••{}) until it is added or linked",
            gmailMessageId, d.getId(), d.getIssuer(), d.getLastFour());
    }

    /** Notes a card seen only in a payment confirmation, so it can be added too. */
    @Transactional
    public void noteCard(Long userId, String issuer, String last4) {
        if ((issuer == null || issuer.isBlank()) && last4 == null) return;
        DetectedCard d = findOrCreate(userId, issuer, last4);
        if (d != null) detectedRepo.save(d);
    }

    public List<DetectedCard> pendingFor(Long userId) {
        return detectedRepo.findByUserIdAndStatusOrderByLastSeenAtDesc(userId, DetectedCard.Status.PENDING);
    }

    public int pendingStatementCount(DetectedCard d) {
        return pending(d).size();
    }

    /** Saves the detected card as a new card and files its statements. */
    @Transactional
    public CreditCard add(Long userId, Long detectedId, String name) {
        DetectedCard d = requirePending(userId, detectedId);
        String cardName = name != null && !name.isBlank() ? name.trim()
            : d.getSuggestedName() != null ? d.getSuggestedName() : (d.getIssuer() != null ? d.getIssuer() : "Credit") + " card";
        CreditCard card = cardRepo.save(CreditCard.builder()
            .userId(userId).name(cardName).issuer(d.getIssuer()).lastFour(d.getLastFour()).build());
        fileAll(userId, d, card);
        d.setStatus(DetectedCard.Status.ADDED);
        d.setLinkedCardId(card.getId());
        detectedRepo.save(d);
        return card;
    }

    /** Ties the detected card to a card already saved (filling in its last four digits if missing) and files its statements. */
    @Transactional
    public CreditCard link(Long userId, Long detectedId, Long cardId) {
        DetectedCard d = requirePending(userId, detectedId);
        CreditCard card = cardRepo.findByIdAndUserId(cardId, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Card not found"));
        if ((card.getLastFour() == null || card.getLastFour().isBlank()) && d.getLastFour() != null) {
            card.setLastFour(d.getLastFour());
            card = cardRepo.save(card);
        }
        fileAll(userId, d, card);
        d.setStatus(DetectedCard.Status.LINKED);
        d.setLinkedCardId(card.getId());
        detectedRepo.save(d);
        return card;
    }

    /** "Not my card": later statements for it are not kept or asked about again. */
    @Transactional
    public void dismiss(Long userId, Long detectedId) {
        DetectedCard d = requirePending(userId, detectedId);
        d.setStatus(DetectedCard.Status.DISMISSED);
        d.setPendingStatementsJson(null);
        detectedRepo.save(d);
    }

    private void fileAll(Long userId, DetectedCard d, CreditCard card) {
        for (PendingStatement p : pending(d)) {
            try {
                ingest.file(userId, card, p.facts(), p.gmailMessageId());
            } catch (RuntimeException e) {
                log.warn("Could not file kept statement {} onto card {}: {}", p.gmailMessageId(), card.getId(), e.getMessage());
            }
        }
        d.setPendingStatementsJson(null);
    }

    /** The open detected card for this issuer and last four, a new one, or null when the user dismissed it. */
    private DetectedCard findOrCreate(Long userId, String issuer, String last4) {
        String key = norm(issuer);
        for (DetectedCard d : detectedRepo.findByUserId(userId)) {
            boolean same = last4 != null && d.getLastFour() != null
                ? last4.equals(d.getLastFour())
                : last4 == null && d.getLastFour() == null && key.equals(norm(d.getIssuer()));
            if (!same) continue;
            if (d.getStatus() == DetectedCard.Status.DISMISSED) return null;
            if (d.getStatus() != DetectedCard.Status.PENDING) continue; // added/linked since; a fresh sighting is a new question
            d.setSeenCount(d.getSeenCount() + 1);
            d.setLastSeenAt(LocalDateTime.now());
            if (d.getIssuer() == null && issuer != null) d.setIssuer(issuer);
            return d;
        }
        return DetectedCard.builder()
            .userId(userId).issuer(issuer).lastFour(last4)
            .suggestedName(issuer != null ? issuer + " card" + (last4 != null ? " ••" + last4 : "") : null)
            .status(DetectedCard.Status.PENDING).build();
    }

    private DetectedCard requirePending(Long userId, Long id) {
        DetectedCard d = detectedRepo.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Detected card not found"));
        if (d.getStatus() != DetectedCard.Status.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This card was already " + d.getStatus().name().toLowerCase(Locale.ROOT) + ".");
        }
        return d;
    }

    private List<PendingStatement> pending(DetectedCard d) {
        if (d.getPendingStatementsJson() == null || d.getPendingStatementsJson().isBlank()) return new ArrayList<>();
        try {
            return new ArrayList<>(objectMapper.readValue(d.getPendingStatementsJson(), new TypeReference<List<PendingStatement>>() {}));
        } catch (Exception e) {
            log.warn("Unreadable kept statements on detected card {}: {}", d.getId(), e.getMessage());
            return new ArrayList<>();
        }
    }

    private String write(List<PendingStatement> pending) {
        try {
            return objectMapper.writeValueAsString(pending);
        } catch (Exception e) {
            throw new IllegalStateException("Could not keep the statement: " + e.getMessage(), e);
        }
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "").replace("bank", "");
    }
}
