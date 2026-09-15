package com.marketai.ai.audit.entity;

import lombok.*;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Every LLM call that influenced a financial record, kept so any imported or flagged
 * transaction can be traced back to the exact prompt and output that produced it.
 *
 * Privacy: the *prompt* stored here is the email/portfolio context that was reasoned over, so
 * it can contain financial detail — it is intentionally persisted (that's the audit value) but
 * must never be written to application logs. Access tokens, passwords and API keys are never
 * part of a prompt in the first place; see AiAuditService for the redaction applied on the way in.
 */
@Entity
@Table(name = "ai_audit_trail", indexes = {
    @Index(name = "idx_aat_user_created", columnList = "user_id, created_at"),
    @Index(name = "idx_aat_task_status", columnList = "task, status"),
    @Index(name = "idx_aat_ref", columnList = "reference_id")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AiAuditTrail {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** EMAIL_CLASSIFY | AMBIGUITY_RESOLVE | ADVISORY | NARRATIVE */
    @Column(nullable = false, length = 40)
    private String task;

    @Column(length = 60)
    private String provider;        // e.g. "ollama"

    @Column(length = 80)
    private String model;           // e.g. "qwen2.5:7b"

    /** What the call was about — a Gmail message id, symbol, or folio. */
    @Column(name = "reference_id", length = 120)
    private String referenceId;

    // columnDefinition = "text", not @Lob: on PostgreSQL a @Lob String becomes an `oid`
    // large-object pointer, which needs explicit lifecycle management and orphans its storage
    // when the row is deleted. Plain text is unbounded here and behaves like a normal column.
    @Column(name = "system_instruction", columnDefinition = "text")
    private String systemInstruction;

    @Column(name = "prompt", columnDefinition = "text")
    private String prompt;

    @Column(name = "raw_output", columnDefinition = "text")
    private String rawOutput;

    @Column(precision = 5, scale = 4)
    private java.math.BigDecimal confidence;

    /** ACCEPTED | REVIEW_REQUIRED | REJECTED | PARSE_FAILED | UNAVAILABLE */
    @Column(nullable = false, length = 30)
    private String status;

    @Column(length = 500)
    private String note;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }
}
