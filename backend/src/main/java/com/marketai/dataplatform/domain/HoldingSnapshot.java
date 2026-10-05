package com.marketai.dataplatform.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A holding as of a date. Two bases are kept per (account, asset): what the ledger implies and
 * what the institution reports. Comparing them is how the ledger is verified.
 */
@Entity
@Table(name = "holding_snapshots",
    uniqueConstraints = @UniqueConstraint(name = "uk_hs_acct_asset_basis", columnNames = {"account_id", "asset_id", "basis"}),
    indexes = @Index(name = "idx_hs_user", columnList = "user_id"))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class HoldingSnapshot {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "family_id") private Long familyId;
    @Column(name = "account_id", nullable = false) private Long accountId;
    @Column(name = "asset_id", nullable = false) private Long assetId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private BasisType basis;

    /** Units (MF) or shares (stock). */
    @Column(precision = 24, scale = 6) private BigDecimal quantity;
    @Column(name = "average_cost", precision = 24, scale = 6) private BigDecimal averageCost;
    @Column(name = "invested_value", precision = 20, scale = 2) private BigDecimal investedValue;
    /** NAV for a fund, last price for a stock. */
    @Column(name = "current_price", precision = 24, scale = 6) private BigDecimal currentPrice;
    @Column(name = "current_value", precision = 20, scale = 2) private BigDecimal currentValue;
    @Column(name = "realized_pnl", precision = 20, scale = 2) private BigDecimal realizedPnl;
    @Column(name = "unrealized_pnl", precision = 20, scale = 2) private BigDecimal unrealizedPnl;
    @Column(precision = 10, scale = 4) private BigDecimal xirr;
    @Column(name = "absolute_return", precision = 10, scale = 4) private BigDecimal absoluteReturn;

    @Column(name = "as_of_date") private LocalDate asOfDate;
    @Column(name = "last_verified_at") private LocalDateTime lastVerifiedAt;
    @Enumerated(EnumType.STRING) @Column(name = "source_type", length = 20) private SourceType sourceType;
    @Column(name = "source_provider", length = 80) private String sourceProvider;
    private double confidence;
    @Column(name = "raw_record_id") private Long rawRecordId;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    @PrePersist @PreUpdate void touch() { updatedAt = LocalDateTime.now(); }
}
