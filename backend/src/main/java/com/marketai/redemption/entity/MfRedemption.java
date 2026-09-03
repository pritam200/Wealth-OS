package com.marketai.redemption.entity;

import lombok.*;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Created when a mutual fund holding is sold (PortfolioService.sellHolding), instead of
 * the sale just disappearing into a single generic Income row. Tracks the redemption's
 * tax classification and stays ACTIVE until the full redeemed amount has been reinvested.
 */
@Entity
@Table(name = "mf_redemptions", indexes = @Index(name = "idx_redemption_user", columnList = "user_id"))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class MfRedemption {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String symbol;

    private String fundName;

    @Column(precision = 18, scale = 4)
    private BigDecimal unitsRedeemed;

    @Column(precision = 18, scale = 4)
    private BigDecimal navAtRedemption;

    @Column(precision = 18, scale = 2)
    private BigDecimal redeemedAmount;

    // Cost basis of the redeemed portion (not the whole original holding).
    @Column(precision = 18, scale = 2)
    private BigDecimal investedValueAtRedemption;

    private LocalDate redemptionDate;
    private Long holdingPeriodDays;

    @Column(length = 10)
    private String gainType; // STCG | LTCG

    @Column(precision = 18, scale = 2)
    private BigDecimal capitalGain;

    @Column(precision = 18, scale = 2)
    private BigDecimal estimatedTax;

    // Running total across all Reinvestment rows below.
    @Builder.Default
    @Column(precision = 18, scale = 2)
    private BigDecimal reinvestedAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(length = 12)
    private String status = "ACTIVE"; // ACTIVE | COMPLETED

    @OneToMany(mappedBy = "redemption", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Reinvestment> reinvestments = new ArrayList<>();

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    public BigDecimal getCashRemaining() {
        BigDecimal amount = redeemedAmount != null ? redeemedAmount : BigDecimal.ZERO;
        BigDecimal reinvested = reinvestedAmount != null ? reinvestedAmount : BigDecimal.ZERO;
        return amount.subtract(reinvested).max(BigDecimal.ZERO);
    }
}
