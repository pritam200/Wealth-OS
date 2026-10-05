package com.marketai.dataplatform.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The canonical financial event. One row per real-world event, whichever sources reported it;
 * the sources themselves are {@link TransactionSource} rows. Calculations read this table.
 */
@Entity
@Table(name = "canonical_transactions",
    uniqueConstraints = @UniqueConstraint(name = "uk_ct_legacy", columnNames = {"user_id", "legacy_transaction_id"}),
    indexes = {
        @Index(name = "idx_ct_user_date", columnList = "user_id, transaction_date"),
        @Index(name = "idx_ct_account_asset", columnList = "account_id, asset_id, transaction_date"),
        @Index(name = "idx_ct_user_recon", columnList = "user_id, reconciliation_status")})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CanonicalTransaction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Version private Long version;

    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "family_id") private Long familyId;
    @Column(name = "account_id", nullable = false) private Long accountId;
    /** Null for pure cash events that concern no asset. */
    @Column(name = "asset_id") private Long assetId;

    @Enumerated(EnumType.STRING) @Column(name = "transaction_type", nullable = false, length = 20)
    private TransactionType transactionType;
    @Column(name = "transaction_date", nullable = false) private LocalDate transactionDate;
    @Column(name = "settlement_date") private LocalDate settlementDate;

    @Column(precision = 24, scale = 6) private BigDecimal quantity;
    @Column(name = "unit_price", precision = 24, scale = 6) private BigDecimal unitPrice;
    @Column(name = "gross_amount", precision = 20, scale = 2) private BigDecimal grossAmount;
    @Column(precision = 20, scale = 2) private BigDecimal fees;
    @Column(precision = 20, scale = 2) private BigDecimal taxes;
    @Column(name = "net_amount", precision = 20, scale = 2) private BigDecimal netAmount;
    @Column(length = 8) @Builder.Default private String currency = "INR";

    /** SPLIT / BONUS / MERGER ratio: each unit held becomes ratioTo / ratioFrom units. */
    @Column(name = "ratio_from", precision = 12, scale = 4) private BigDecimal ratioFrom;
    @Column(name = "ratio_to", precision = 12, scale = 4) private BigDecimal ratioTo;

    /** MERGER: the symbol whose shares replace the old ones (each {@code ratioFrom} held becomes {@code ratioTo} of it). */
    @Column(name = "new_symbol", length = 40) private String newSymbol;
    /** FD_CREATION: annual rate in percent and the maturity date, as the source reported them. */
    @Column(name = "interest_rate", precision = 8, scale = 3) private BigDecimal interestRate;
    @Column(name = "maturity_date") private LocalDate maturityDate;
    /** Where a non-trade event was booked in the rest of the app once the user confirmed it, e.g. "income:12" or "fd:5". */
    @Column(name = "portfolio_ref", length = 40) private String portfolioRef;

    /** The most reliable source that has reported this event — what the figures above come from. */
    @Enumerated(EnumType.STRING) @Column(name = "source_type", nullable = false, length = 20)
    private SourceType sourceType;
    @Column(name = "source_provider", length = 80) private String sourceProvider;
    @Column(name = "source_reference", length = 120) private String sourceReference;
    @Column(name = "source_timestamp") private LocalDateTime sourceTimestamp;
    @Column(name = "ingested_at", nullable = false) private LocalDateTime ingestedAt;

    @Column(nullable = false) private double confidence;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    @Builder.Default private TxnStatus status = TxnStatus.PENDING_RECONCILIATION;
    @Enumerated(EnumType.STRING) @Column(name = "reconciliation_status", nullable = false, length = 30)
    @Builder.Default private ReconStatus reconciliationStatus = ReconStatus.PENDING;
    @Column(name = "last_verified_at") private LocalDateTime lastVerifiedAt;

    /** The row in the pre-canonical {@code transactions} table this event was migrated from or mirrors. */
    @Column(name = "legacy_transaction_id") private Long legacyTransactionId;
    /** Shared by the legs of one event (the two sides of a fund switch). */
    @Column(name = "link_group", length = 100) private String linkGroup;
    @Column(length = 500) private String notes;

    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;

    @PrePersist void onCreate() {
        createdAt = LocalDateTime.now(); updatedAt = createdAt;
        if (ingestedAt == null) ingestedAt = createdAt;
    }
    @PreUpdate void onUpdate() { updatedAt = LocalDateTime.now(); }
}
