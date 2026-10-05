package com.marketai.dataplatform.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Something that can be owned: a listed security, a fund scheme, a deposit product. One model for
 * every asset class, so transactions and holdings do not fork per class.
 */
@Entity
@Table(name = "financial_assets", indexes = {@Index(name = "idx_fas_isin", columnList = "isin"), @Index(name = "idx_fas_name", columnList = "asset_class, name_key")},
    uniqueConstraints = @UniqueConstraint(name = "uk_fas_class_key", columnNames = {"asset_class", "asset_key"}))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FinancialAsset {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING) @Column(name = "asset_class", nullable = false, length = 20)
    private AssetClass assetClass;
    /** ISIN when known, otherwise the upper-cased symbol. The identity of the asset within its class. */
    @Column(name = "asset_key", nullable = false, length = 80) private String assetKey;
    @Column(length = 60) private String symbol;
    @Column(length = 20) private String isin;
    @Column(length = 250) private String name;
    /** The name lower-cased to letters and digits, so one fund spelled two ways resolves to one asset. */
    @Column(name = "name_key", length = 160) private String nameKey;
    @Column(name = "amfi_code", length = 20) private String amfiCode;
    @Column(length = 8) @Builder.Default private String currency = "INR";
    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @PrePersist void onCreate() { createdAt = LocalDateTime.now(); }
}
