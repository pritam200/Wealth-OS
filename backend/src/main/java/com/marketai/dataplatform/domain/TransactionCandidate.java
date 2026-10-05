package com.marketai.dataplatform.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A proposed transaction from any source, before it is tied to the ledger. Authoritative sources
 * promote straight to a canonical transaction; an email waits as a candidate until a stronger
 * source corroborates it. The matching decision is stored with its explanation.
 */
@Entity
@Table(name = "transaction_candidates",
    uniqueConstraints = @UniqueConstraint(name = "uk_tc_raw", columnNames = {"raw_record_id"}),
    indexes = @Index(name = "idx_tc_user_status", columnList = "user_id, status"))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TransactionCandidate {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "raw_record_id", nullable = false) private Long rawRecordId;
    @Column(name = "account_id") private Long accountId;
    @Column(name = "asset_id") private Long assetId;

    @Enumerated(EnumType.STRING) @Column(name = "transaction_type", length = 20) private TransactionType transactionType;
    @Column(name = "transaction_date") private LocalDate transactionDate;
    @Column(name = "settlement_date") private LocalDate settlementDate;
    @Column(precision = 24, scale = 6) private BigDecimal quantity;
    @Column(name = "unit_price", precision = 24, scale = 6) private BigDecimal unitPrice;
    @Column(name = "gross_amount", precision = 20, scale = 2) private BigDecimal grossAmount;
    @Column(precision = 20, scale = 2) private BigDecimal fees;
    @Column(precision = 20, scale = 2) private BigDecimal taxes;
    @Column(name = "net_amount", precision = 20, scale = 2) private BigDecimal netAmount;
    @Column(length = 8) private String currency;

    @Enumerated(EnumType.STRING) @Column(name = "source_type", nullable = false, length = 20) private SourceType sourceType;
    @Column(name = "source_provider", length = 80) private String sourceProvider;
    @Column(name = "source_reference", length = 120) private String sourceReference;
    private double confidence;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    @Builder.Default private CandidateStatus status = CandidateStatus.PENDING_RECONCILIATION;
    @Column(name = "matched_transaction_id") private Long matchedTransactionId;
    @Column(name = "match_kind", length = 30) private String matchKind;
    @Column(name = "decision_explanation", columnDefinition = "text") private String decisionExplanation;
    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(name = "decided_at") private LocalDateTime decidedAt;
    @PrePersist void onCreate() { createdAt = LocalDateTime.now(); }
}
