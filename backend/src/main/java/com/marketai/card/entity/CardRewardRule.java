package com.marketai.card.entity;

import lombok.*;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A reward rate that was true for a period of time.
 *
 * Reward terms are hand-curated everywhere in this category — no Indian issuer publishes a
 * machine-readable feed, and issuers devalue terms frequently. A rate stored as a bare
 * constant therefore silently becomes a lie, and any "net value" computed from it is wrong in
 * a way nothing surfaces. Modelling rules as effective-dated rows with a verification date
 * lets the optimizer show its own staleness instead of asserting a stale number confidently.
 *
 * Caps live here too, because they make optimization path-dependent: the best card for a
 * purchase depends on how much of the monthly cap is already consumed.
 */
@Entity
@Table(name = "card_reward_rules", indexes = {
    @Index(name = "idx_crr_card_cat", columnList = "card_name, category"),
    @Index(name = "idx_crr_effective", columnList = "effective_from, effective_to")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CardRewardRule {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Matches CatalogCard.name so curated rules can override the static catalog. */
    @Column(name = "card_name", nullable = false, length = 160)
    private String cardName;

    @Column(nullable = false, length = 60)
    private String category;

    /** Percent back, e.g. 5.00 for 5%. */
    @Column(name = "reward_rate", nullable = false, precision = 6, scale = 3)
    private BigDecimal rewardRate;

    /** Max reward earnable per calendar month, or null for uncapped. */
    @Column(name = "monthly_cap_amount", precision = 18, scale = 2)
    private BigDecimal monthlyCapAmount;

    /** Spend needed in a year for the annual fee to be waived, or null if no waiver. */
    @Column(name = "annual_fee_waiver_spend", precision = 18, scale = 2)
    private BigDecimal annualFeeWaiverSpend;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /** Null means "still current". */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    /** Where this was read from — a T&C PDF or a community thread. */
    @Column(name = "source_url", length = 500)
    private String sourceUrl;

    /** When a human last confirmed this against the source. Drives the staleness warning. */
    @Column(name = "last_verified_at")
    private LocalDate lastVerifiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }

    public boolean isCurrentOn(LocalDate date) {
        if (effectiveFrom != null && date.isBefore(effectiveFrom)) return false;
        return effectiveTo == null || !date.isAfter(effectiveTo);
    }

    /** Rules unverified for this long are reported as stale rather than trusted silently. */
    public boolean isStale(LocalDate today, long maxAgeDays) {
        return lastVerifiedAt == null || lastVerifiedAt.plusDays(maxAgeDays).isBefore(today);
    }
}
