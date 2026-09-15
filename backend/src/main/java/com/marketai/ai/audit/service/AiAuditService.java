package com.marketai.ai.audit.service;

import com.marketai.ai.audit.entity.AiAuditTrail;
import com.marketai.ai.audit.repository.AiAuditTrailRepository;
import com.marketai.ai.llm.LlmCompletion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Writes the AI audit trail. Recording must never break the operation being audited, so every
 * failure here is swallowed with a warning — losing an audit row is bad, but failing an
 * otherwise-valid import because its audit row wouldn't save is worse.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiAuditService {

    private final AiAuditTrailRepository repo;

    private static final int MAX_FIELD = 20_000;

    // Defence in depth: prompts are built from email text and portfolio context and should
    // never carry a credential, but statement emails do sometimes quote passwords, and OAuth
    // tokens can appear in raw headers. Redact on the way in so a leak can't be persisted.
    private static final Pattern[] SECRET_PATTERNS = {
        Pattern.compile("(?i)(password|passwd|pwd)\\s*[:=]\\s*\\S+"),
        Pattern.compile("(?i)(api[-_]?key|secret|token)\\s*[:=]\\s*\\S+"),
        Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/-]{12,}={0,2}"),
        Pattern.compile("\\bya29\\.[A-Za-z0-9._-]+"),          // Google OAuth access tokens
        Pattern.compile("\\beyJ[A-Za-z0-9._-]{20,}"),          // JWTs
    };

    static String redact(String s) {
        if (s == null) return null;
        String out = s;
        for (Pattern p : SECRET_PATTERNS) {
            out = p.matcher(out).replaceAll("[REDACTED]");
        }
        return out.length() > MAX_FIELD ? out.substring(0, MAX_FIELD) + "…[truncated]" : out;
    }

    public void record(Long userId, String task, String referenceId,
                       String systemInstruction, String prompt,
                       LlmCompletion completion, Double confidence,
                       String status, String note) {
        try {
            repo.save(AiAuditTrail.builder()
                .userId(userId)
                .task(task)
                .referenceId(referenceId)
                .provider(completion != null ? completion.getProvider() : null)
                .model(completion != null ? completion.getModel() : null)
                .systemInstruction(redact(systemInstruction))
                .prompt(redact(prompt))
                .rawOutput(completion != null ? redact(completion.getText()) : null)
                .confidence(confidence != null ? BigDecimal.valueOf(confidence) : null)
                .status(status)
                .note(note != null && note.length() > 500 ? note.substring(0, 500) : note)
                .latencyMs(completion != null ? completion.getLatencyMs() : null)
                .promptTokens(completion != null ? completion.getPromptTokens() : null)
                .completionTokens(completion != null ? completion.getCompletionTokens() : null)
                .build());
        } catch (Exception e) {
            log.warn("Could not write AI audit row for task {} ref {}: {}", task, referenceId, e.getMessage());
        }
    }

    /** Records a call that never produced output (model down, parse failure). */
    public void recordFailure(Long userId, String task, String referenceId,
                              String systemInstruction, String prompt,
                              String status, String note) {
        record(userId, task, referenceId, systemInstruction, prompt, null, null, status, note);
    }
}
