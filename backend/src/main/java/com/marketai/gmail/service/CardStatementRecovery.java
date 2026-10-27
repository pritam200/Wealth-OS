package com.marketai.gmail.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the card statements that were read before ₹0 and credit-balance statements, and
 * summaries printed as a table, were accepted. Their figures are still in the audit trail —
 * what the model returned and the text it read — so they are re-checked from there at startup,
 * with no new model call. A statement already on file, or already held for a detected card,
 * is skipped, so this does nothing once everything is kept.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CardStatementRecovery {

    private static final String TASK = "EMAIL_LLM_EXTRACT";

    private final JdbcTemplate jdbc;
    private final EmailLLMParserService parser;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        // Own thread: nothing here should hold up startup.
        Thread t = new Thread(this::run, "card-statement-recovery");
        t.setDaemon(true);
        t.start();
    }

    void run() {
        try {
            List<Map<String, Object>> reads = jdbc.queryForList(
                "select a.user_id, a.reference_id, max(a.created_at) as read_at from ai_audit_trail a"
                + " where a.task = ? and a.raw_output like '%\"card_statement\"%'"
                + " and not exists (select 1 from card_statements s where s.user_id = a.user_id and s.source_email_id = a.reference_id)"
                + " and not exists (select 1 from detected_cards d where d.user_id = a.user_id"
                + "   and d.pending_statements_json like '%' || a.reference_id || '%')"
                + " group by a.user_id, a.reference_id", TASK);
            int kept = 0;
            for (Map<String, Object> r : reads) {
                Long userId = ((Number) r.get("user_id")).longValue();
                String ref = (String) r.get("reference_id");
                Timestamp at = (Timestamp) r.get("read_at");
                if (recover(userId, ref, at)) kept++;
            }
            if (!reads.isEmpty()) log.info("Card statement recovery: {} of {} earlier reads kept", kept, reads.size());
        } catch (RuntimeException e) {
            log.warn("Card statement recovery skipped: {}", e.getMessage());
        }
    }

    /** The read that produced the latest card statement figures: every part read in the same run. */
    boolean recover(Long userId, String ref, Timestamp at) {
        List<Map<String, Object>> parts = jdbc.queryForList(
            "select prompt, raw_output from ai_audit_trail where user_id = ? and reference_id = ? and task = ?"
            + " and raw_output is not null and created_at between ? and ? order by created_at",
            userId, ref, TASK, new Timestamp(at.getTime() - 10 * 60_000), new Timestamp(at.getTime() + 60_000));
        Set<String> chunks = new LinkedHashSet<>();
        List<String> outputs = new ArrayList<>();
        for (Map<String, Object> p : parts) {
            String prompt = (String) p.get("prompt");
            if (prompt != null) {
                int body = prompt.indexOf("\n\n");   // after the From: / Subject: header
                chunks.add(body >= 0 ? prompt.substring(body + 2) : prompt);
            }
            outputs.add((String) p.get("raw_output"));
        }
        try {
            return parser.recoverCardStatement(userId, ref, outputs, String.join("\n", chunks));
        } catch (RuntimeException e) {
            log.warn("Could not recover the card statement in {}: {}", ref, e.getMessage());
            return false;
        }
    }
}
