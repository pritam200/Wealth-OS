package com.marketai.dataplatform.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A discrepancy the system found and a person must see. It is closed only by a recorded
 * decision, or by the system with a stated reason; it is never dropped silently.
 */
@Entity
@Table(name = "ledger_issues",
    uniqueConstraints = @UniqueConstraint(name = "uk_li_user_key", columnNames = {"user_id", "issue_key"}),
    indexes = @Index(name = "idx_li_user_status", columnList = "user_id, status"))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class LedgerIssue {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "account_id") private Long accountId;
    @Column(name = "asset_id") private Long assetId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private IssueType type;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private IssueSeverity severity;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) @Builder.Default private IssueStatus status = IssueStatus.OPEN;
    /** Stable identity so a re-detected problem updates this row instead of adding another. */
    @Column(name = "issue_key", nullable = false, length = 200) private String issueKey;
    @Column(length = 250) private String title;
    @Column(columnDefinition = "text") private String description;
    @Column(name = "expected_value", length = 100) private String expectedValue;
    @Column(name = "observed_value", length = 100) private String observedValue;
    @Column(precision = 24, scale = 6) private BigDecimal difference;
    /** Comma-separated {@link SuspectedCause}, most likely first. */
    @Column(name = "suspected_causes", length = 300) private String suspectedCauses;
    @Column(name = "transaction_id") private Long transactionId;
    @Column(name = "other_transaction_id") private Long otherTransactionId;
    @Column(name = "detected_at", nullable = false) private LocalDateTime detectedAt;
    @Column(name = "last_seen_at", nullable = false) private LocalDateTime lastSeenAt;
    @Column(name = "resolved_at") private LocalDateTime resolvedAt;
    @Enumerated(EnumType.STRING) @Column(name = "resolution_action", length = 30) private ResolutionAction resolutionAction;
    @Column(name = "resolution_note", length = 500) private String resolutionNote;
    /** A user id, or SYSTEM. */
    @Column(name = "resolved_by", length = 20) private String resolvedBy;
}
