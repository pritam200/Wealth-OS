package com.marketai.sync.entity;

import com.marketai.auth.entity.User;
import lombok.*;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * A durable unit of ingestion work.
 *
 * Why this exists: Gmail sync used to run inside the HTTP request. A full scan fetches each
 * message individually and, for anything the deterministic parsers can't read, spends ~8s in
 * the local classifier — so a few hundred emails is tens of minutes of work sitting in a
 * request that will time out long before it finishes. Worse, a timeout left no record of how
 * far the work got.
 *
 * Persisting the job separates "the user asked for a sync" from "the sync ran", so a crash or
 * restart resumes rather than silently losing the request.
 */
@Entity
@Table(name = "sync_jobs", indexes = {
    @Index(name = "idx_sj_status_created", columnList = "status, created_at"),
    @Index(name = "idx_sj_user_status", columnList = "user_id, status"),
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SyncJob {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)

    @EqualsAndHashCode.Include
    @ToString.Include    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SyncJobType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SyncJobStatus status = SyncJobStatus.QUEUED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SyncTrigger trigger;

    /** Free-form job input, e.g. the lookback window or the historyId to resume from. */
    @Column(length = 500)
    private String parameters;

    @Column(nullable = false)
    @Builder.Default
    private Integer attempts = 0;

    @Column(name = "max_attempts", nullable = false)
    @Builder.Default
    private Integer maxAttempts = 3;

    /* Progress, updated as the job runs so the UI can show movement rather than a spinner. */
    @Column(name = "items_total")
    private Integer itemsTotal;

    @Column(name = "items_processed")
    @Builder.Default
    private Integer itemsProcessed = 0;

    @Column(name = "result_summary", columnDefinition = "text")
    private String resultSummary;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    /**
     * Set while a worker holds the job. Lets a restart detect jobs that were RUNNING when the
     * process died and requeue them instead of leaving them stuck forever.
     */
    @Column(name = "claimed_by", length = 80)
    private String claimedBy;

    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }

    public boolean isTerminal() {
        return status == SyncJobStatus.SUCCEEDED
            || status == SyncJobStatus.FAILED
            || status == SyncJobStatus.CANCELLED;
    }
}
