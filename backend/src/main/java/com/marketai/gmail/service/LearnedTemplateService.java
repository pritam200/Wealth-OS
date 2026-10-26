package com.marketai.gmail.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketai.gmail.entity.LearnedTemplate;
import com.marketai.gmail.ledger.FinancialEventLedger;
import com.marketai.gmail.parser.BankAlertParsers;
import com.marketai.gmail.parser.LearnedTemplates;
import com.marketai.gmail.repository.LearnedTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Keeps the alert wordings learned from the model's reads and reads new alerts with them, so a
 * sender's alerts go to the model once per wording rather than once per email.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LearnedTemplateService {

    /** A sender with more wordings than this is not a template sender; nothing more is learned. */
    static final int MAX_PER_SENDER_SUBJECT = 10;

    private final LearnedTemplateRepository repo;

    /** Reads an alert with a learned wording for this sender and subject, or empty. */
    public Optional<BankAlertParsers.Result> read(Long userId, String senderDomain, String subject, String text) {
        if (userId == null || senderDomain == null || text == null) return Optional.empty();
        for (LearnedTemplate lt : repo.findByUserIdAndSenderDomainAndSubjectShapeOrderByTimesUsedDesc(
                userId, senderDomain, LearnedTemplates.subjectShape(subject))) {
            Optional<JsonNode> t = LearnedTemplates.read(new LearnedTemplates.Shape(lt.getPattern(), lt.getConstants(), lt.getSample()), text);
            if (t.isEmpty()) continue;
            lt.setTimesUsed(lt.getTimesUsed() + 1);
            lt.setLastUsedAt(LocalDateTime.now());
            repo.save(lt);
            return Optional.of(new BankAlertParsers.Result(LearnedTemplates.asExtraction(t.get()), "learned-" + lt.getId()));
        }
        return Optional.empty();
    }

    /** Learns the wording of one transaction the model read and the checks accepted. Never throws. */
    public void learn(Long userId, String senderDomain, String subject, String text, JsonNode t) {
        try {
            if (userId == null || senderDomain == null) return;
            String shape = LearnedTemplates.subjectShape(subject);
            if (repo.findByUserIdAndSenderDomainAndSubjectShapeOrderByTimesUsedDesc(userId, senderDomain, shape).size()
                    >= MAX_PER_SENDER_SUBJECT) return;
            Optional<LearnedTemplates.Shape> learned = LearnedTemplates.learn(text, t);
            if (learned.isEmpty()) return;
            String hash = sha256(senderDomain + "|" + shape + "|" + learned.get().pattern());
            if (repo.existsByUserIdAndPatternHash(userId, hash)) return;
            String sample = maskSample(learned.get().sample());
            repo.save(LearnedTemplate.builder().userId(userId).senderDomain(senderDomain).subjectShape(shape)
                .pattern(learned.get().pattern()).patternHash(hash).constants(learned.get().constants())
                .sample(sample != null && sample.length() > 500 ? sample.substring(0, 500) : sample).build());
            log.info("Learned an alert wording from {} for user {}", senderDomain, userId);
        } catch (Exception e) {
            log.debug("Alert wording not learned from {}: {}", senderDomain, e.getMessage());
        }
    }

    /** Account numbers ("XX4521", "A/C XXXXX953801") and references hidden entirely, not to their last four. */
    static String maskSample(String sample) {
        String s = FinancialEventLedger.mask(sample);
        if (s == null) return null;
        return s.replaceAll("(?i)\\b[x*•]+\\d+", "XX••••").replaceAll("\\d{6,}", "••••");
    }

    public List<LearnedTemplate> list(Long userId) {
        return repo.findByUserIdOrderByTimesUsedDescCreatedAtDesc(userId);
    }

    public boolean forget(Long userId, Long id) {
        if (!repo.existsByIdAndUserId(id, userId)) return false;
        repo.deleteById(id);
        return true;
    }

    private static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }
}
