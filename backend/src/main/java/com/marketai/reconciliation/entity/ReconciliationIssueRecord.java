package com.marketai.reconciliation.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A reconciliation finding with a lifecycle, so a problem stays visible until it is actually
 * gone rather than existing only for the length of one report request.
 *
 * <p>OPEN when found; ACKNOWLEDGED when the user has seen it and accepts it for now (it stays
 * listed); IGNORED when the user says it isn't a problem (it is kept out of the open list for as
 * long as the check keeps finding it); RESOLVED automatically once the check no longer finds it.
 * A resolved issue that comes back is reopened, and the user can reopen an acknowledged or
 * ignored one.
 */
@Entity
@Table(name = "reconciliation_issues",
    uniqueConstraints = @UniqueConstraint(name = "uq_reconciliation_issue_key", columnNames = {"user_id", "issue_key"}),
    indexes = @Index(name = "idx_reconciliation_issue_user_status", columnList = "user_id, status"))
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ReconciliationIssueRecord {

    public static final String OPEN = "OPEN";
    public static final String ACKNOWLEDGED = "ACKNOWLEDGED";
    public static final String RESOLVED = "RESOLVED";
    public static final String IGNORED = "IGNORED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Stable identity of the finding across runs: domain, type and the record it concerns. */
    @Column(name = "issue_key", nullable = false, length = 200)
    private String issueKey;

    @Column(nullable = false, length = 40)
    private String domain;

    @Column(nullable = false, length = 80)
    private String type;

    @Column(nullable = false, length = 10)
    private String severity;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "reference_id")
    private Long referenceId;

    /** The check that found it, so each check's result can be shown. */
    @Column(name = "check_id", length = 80)
    private String checkId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(length = 500)
    private String note;
}
