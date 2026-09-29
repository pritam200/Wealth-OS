package com.marketai.ai.llm.usage;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One model call, as metadata only: which task, provider, model and prompt version, how long it
 * took, whether it worked and why not. No prompt, no output, no user and no document content —
 * those stay in the per-record AI audit trail, which exists to show a person where their own
 * record came from. Kept for {@link LlmUsageService#RETENTION_DAYS} days.
 */
@Entity
@Table(name = "llm_call_log", indexes = @Index(name = "idx_llm_call_created", columnList = "created_at"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LlmCallLog {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(length = 40, nullable = false)
    private String task;

    @Column(length = 12, nullable = false)
    private String provider;

    @Column(length = 80)
    private String model;

    @Column(name = "prompt_version", length = 60)
    private String promptVersion;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(nullable = false)
    private boolean success;

    /** {@code LlmErrorCategory} name when the call failed. */
    @Column(name = "error_category", length = 30)
    private String errorCategory;

    /** This call went to the fallback provider because the task's own one failed. */
    @Column(nullable = false)
    private boolean fallback;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;
}
