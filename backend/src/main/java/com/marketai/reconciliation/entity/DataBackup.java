package com.marketai.reconciliation.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A snapshot of one user's financial tables, taken before any rebuild. The rebuild refuses to
 * run without a fresh one, and each snapshot authorises one rebuild. The payload is plain JSON
 * (table → rows) so it can be downloaded and restored by hand if a rebuild removed something it
 * should not have.
 */
@Entity
@Table(name = "data_backups", indexes = @Index(name = "idx_data_backup_user", columnList = "user_id"))
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class DataBackup {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /** table → row count, e.g. {"expenses":120,"transactions":48}. */
    @Column(name = "row_counts", length = 1000)
    private String rowCounts;

    // TEXT, not @Lob: on PostgreSQL a @Lob String becomes a large-object OID.
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    /** Set when a rebuild used this backup; a backup authorises one rebuild. */
    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    @Column(name = "applied_summary", length = 2000)
    private String appliedSummary;
}
