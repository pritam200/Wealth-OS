package com.marketai.gmail.service;

import com.marketai.ai.review.entity.EmailReviewItem;
import com.marketai.ai.review.entity.ReviewStatus;
import com.marketai.ai.review.repository.EmailReviewItemRepository;
import com.marketai.auth.entity.User;
import com.marketai.auth.repository.UserRepository;
import com.marketai.gmail.ledger.FinancialEventLedger;
import com.marketai.gmail.parser.ParsedEmail;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Transactions held for review only because their quoted line was not found verbatim — a word
 * the PDF wrapped across lines, or a reference the quote masked — checked again at startup
 * against the text they were read from, now that the check allows for both. Nothing is asked
 * of the model: what it returned is kept on the review item, and the text it read is in the
 * audit trail. A line that now passes is booked through the normal import (duplicate check
 * included) and its review item closed; anything else stays for the user.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HeldItemRecheck {

    static final String NOT_FOUND_REASON = "The figures for %could not be found in the email text%";
    static final String NOTE = "Re-checked automatically: the quoted line is in the statement.";

    private final JdbcTemplate jdbc;
    private final EmailReviewItemRepository items;
    private final EmailLLMParserService parser;
    private final ParsedEmailImporter importer;
    private final FinancialEventLedger ledger;
    private final UserRepository users;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        // Own thread: nothing here should hold up startup.
        Thread t = new Thread(this::run, "held-item-recheck");
        t.setDaemon(true);
        t.start();
    }

    void run() {
        try {
            List<Long> ids = jdbc.queryForList(
                "select id from email_review_items where status = 'PENDING' and review_reason like ? order by gmail_message_id, item_index",
                Long.class, NOT_FOUND_REASON);
            if (ids.isEmpty()) return;
            Map<String, List<EmailReviewItem>> byEmail = new LinkedHashMap<>();
            for (EmailReviewItem i : items.findAllById(ids)) {
                byEmail.computeIfAbsent(i.getUserId() + "|" + i.getGmailMessageId(), k -> new java.util.ArrayList<>()).add(i);
            }
            int booked = 0;
            for (List<EmailReviewItem> held : byEmail.values()) booked += recheck(held);
            log.info("Held item re-check: {} of {} held line(s) booked", booked, ids.size());
        } catch (RuntimeException e) {
            log.warn("Held item re-check skipped: {}", e.getMessage());
        }
    }

    /** One email's held lines, in statement order. */
    int recheck(List<EmailReviewItem> held) {
        held.sort(java.util.Comparator.comparingInt(EmailReviewItem::getItemIndex));
        EmailReviewItem first = held.get(0);
        User user = users.findById(first.getUserId()).orElse(null);
        String source = sourceText(first.getUserId(), first.getGmailMessageId());
        if (user == null || source.isBlank()) return 0;
        Map<String, Integer> occurrences = new HashMap<>();
        int booked = 0;
        for (EmailReviewItem item : held) {
            double confidence = item.getConfidence() != null ? item.getConfidence().doubleValue() : 0;
            boolean fromDocument = item.getItemIndex() >= 1_000_000;
            ParsedEmail pe = parser.recheckHeld(item.getExtractedFields(), source, user.getName(), fromDocument, confidence, occurrences);
            if (pe == null) continue;
            try {
                importer.importParsedEmail(user.getId(), user, pe, item.getGmailMessageId(), null);
            } catch (Exception e) {
                log.debug("Held line {} not booked on re-check: {}", item.getId(), e.getMessage());
                continue;
            }
            item.setStatus(ReviewStatus.ACCEPTED);
            item.setResolvedAt(LocalDateTime.now());
            item.setResolutionNote(NOTE);
            items.save(item);
            ledger.onReviewDecision(user.getId(), item.getGmailMessageId(), item.getItemIndex(), true, NOTE);
            booked++;
        }
        return booked;
    }

    /** Every part the extractor read for the email, without each prompt's From/Subject header. */
    private String sourceText(Long userId, String gmailMessageId) {
        Set<String> chunks = new LinkedHashSet<>();
        for (String prompt : jdbc.queryForList(
                "select prompt from ai_audit_trail where user_id = ? and reference_id = ? and task = 'EMAIL_LLM_EXTRACT'"
                + " and prompt is not null order by created_at", String.class, userId, gmailMessageId)) {
            int body = prompt.indexOf("\n\n");
            chunks.add(body >= 0 ? prompt.substring(body + 2) : prompt);
        }
        return String.join("\n", chunks);
    }
}
