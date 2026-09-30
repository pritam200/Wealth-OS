package com.marketai.research.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One stored research result. Keyed by what produced it — subject, session, data snapshot,
 * prompt versions, provider and model — so a cached answer is reused only for exactly the same
 * inputs, and a change of provider or model never overwrites earlier research.
 * Research is advisory text; no financial record reads from this table.
 */
@Entity
@Table(name = "research_records",
       uniqueConstraints = @UniqueConstraint(name = "uk_research_key",
           columnNames = {"subject_type", "symbol", "user_scope", "market_date", "snapshot_hash", "prompt_version", "provider", "model"}),
       indexes = @Index(name = "idx_research_latest", columnList = "subject_type, symbol, user_scope, created_at"))
@Getter @Setter @NoArgsConstructor
public class ResearchRecord {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subject_type", nullable = false, length = 20)
    private String subjectType;

    @Column(nullable = false, length = 40)
    private String symbol;

    /** The user whose portfolio was part of the context; 0 when none was. */
    @Column(name = "user_scope", nullable = false)
    private Long userScope;

    @Column(name = "market_date")
    private LocalDate marketDate;

    @Column(name = "snapshot_hash", nullable = false, length = 64)
    private String snapshotHash;

    /** Analyst and review prompt tags, e.g. "research-analyst-v1+research-review-v1". */
    @Column(name = "prompt_version", nullable = false, length = 120)
    private String promptVersion;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(nullable = false, length = 120)
    private String model;

    /** OK | PARTIAL */
    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** The full ResearchResult as JSON. */
    @Column(nullable = false, columnDefinition = "text")
    private String payload;
}
