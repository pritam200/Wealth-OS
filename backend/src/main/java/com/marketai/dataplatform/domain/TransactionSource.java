package com.marketai.dataplatform.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One source's report of a canonical transaction. A transaction reported by an aggregator, an
 * email and a statement has one canonical row and three of these, each keeping what that source
 * said, so nothing is lost when the canonical figures take the most reliable source's values.
 */
@Entity
@Table(name = "transaction_sources",
    uniqueConstraints = @UniqueConstraint(name = "uk_ts_txn_raw", columnNames = {"transaction_id", "raw_record_id"}),
    indexes = {@Index(name = "idx_ts_txn", columnList = "transaction_id"),
               @Index(name = "idx_ts_ref", columnList = "source_type, source_provider, source_reference")})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TransactionSource {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "transaction_id", nullable = false) private Long transactionId;
    @Column(name = "raw_record_id", nullable = false) private Long rawRecordId;
    @Enumerated(EnumType.STRING) @Column(name = "source_type", nullable = false, length = 20) private SourceType sourceType;
    @Column(name = "source_provider", length = 80) private String sourceProvider;
    @Column(name = "source_reference", length = 120) private String sourceReference;
    @Column(name = "source_timestamp") private LocalDateTime sourceTimestamp;

    @Column(name = "reported_type", length = 20) private String reportedType;
    @Column(name = "reported_date") private LocalDate reportedDate;
    @Column(name = "reported_quantity", precision = 24, scale = 6) private BigDecimal reportedQuantity;
    @Column(name = "reported_unit_price", precision = 24, scale = 6) private BigDecimal reportedUnitPrice;
    @Column(name = "reported_gross_amount", precision = 20, scale = 2) private BigDecimal reportedGrossAmount;
    @Column(name = "reported_net_amount", precision = 20, scale = 2) private BigDecimal reportedNetAmount;

    @Column(name = "record_confidence") private double recordConfidence;
    /** How this report was tied to the transaction: NEW, EXACT_REFERENCE, COMPOSITE_EXACT, COMPOSITE_TOLERANT. */
    @Column(name = "match_kind", length = 30) private String matchKind;
    @Column(name = "match_explanation", length = 1000) private String matchExplanation;
    @Column(name = "linked_at", nullable = false) private LocalDateTime linkedAt;
    @PrePersist void onCreate() { if (linkedAt == null) linkedAt = LocalDateTime.now(); }
}
