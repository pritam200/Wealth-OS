package com.marketai.dataplatform.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** One execution of a sync, with the counts the UI shows. */
@Entity
@Table(name = "financial_sync_runs", indexes = @Index(name = "idx_fsr_user_started", columnList = "user_id, started_at"))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FinancialSyncRun {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "connection_id") private Long connectionId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private SyncKind kind;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) @Builder.Default private SyncStatus status = SyncStatus.RUNNING;
    @Column(name = "started_at", nullable = false) private LocalDateTime startedAt;
    @Column(name = "finished_at") private LocalDateTime finishedAt;
    @Column(name = "records_fetched") @Builder.Default private int recordsFetched = 0;
    @Column(name = "records_created") @Builder.Default private int recordsCreated = 0;
    @Column(name = "records_updated") @Builder.Default private int recordsUpdated = 0;
    @Column(name = "records_duplicated") @Builder.Default private int recordsDuplicated = 0;
    @Column(name = "records_rejected") @Builder.Default private int recordsRejected = 0;
    @Column(name = "records_reconciled") @Builder.Default private int recordsReconciled = 0;
    @Column(columnDefinition = "text") private String errors;
}
