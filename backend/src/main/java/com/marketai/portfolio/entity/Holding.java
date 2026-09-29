package com.marketai.portfolio.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "holdings",
    uniqueConstraints = @UniqueConstraint(name = "uk_holding_portfolio_symbol", columnNames = {"portfolio_id", "symbol"}),
    indexes = {
        @Index(name = "idx_holding_portfolio", columnList = "portfolio_id"),
        @Index(name = "idx_holding_symbol", columnList = "symbol")
})
// Lombok's @Data generates equals, hashCode and toString over *every* field, including JPA
// relations. On an entity with a bidirectional mapping that recurses forever:
// Holding.hashCode() reads its portfolio, Portfolio.hashCode() reads its holdings list, which
// reads this holding again — a StackOverflowError, reproduced and confirmed before this fix.
// The same recursion applies to toString(), so simply logging an entity crashed the thread.
//
// On lazy relations it is also a LazyInitializationException (or a silent N+1) as soon as
// equals/hashCode is called outside a session.
//
// Identity is therefore the primary key alone, which is what JPA semantics actually mean by
// "the same row", and relations are excluded from toString.
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Holding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)

    @EqualsAndHashCode.Include
    @ToString.Include    private Long id;

    /** Optimistic lock: a sync and a user edit changing the same record at once can't silently
     *  overwrite each other — the second write fails and is retried or reported. */
    @Version
    @Column(name = "version")
    @lombok.EqualsAndHashCode.Exclude
    private Long version;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "portfolio_id", nullable = false)
    private Portfolio portfolio;

    @Column(nullable = false, length = 50)
    private String symbol;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal quantity;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal averageCost;

    /** Market price or NAV. Scale 4 — MF NAVs are published to four decimals, and rounding them
     *  to two moves a large folio's value by rupees. */
    @Column(precision = 18, scale = 4)
    private BigDecimal currentPrice;

    /** The trading day {@link #currentPrice} is from — the quote date, or the NAV date. Null
     *  when the price has never been set, or was set before this was tracked; such a price
     *  reads as STALE ("date unknown") until the next refresh dates it. It is deliberately not
     *  backfilled from updated_at, which is later than the price and would pass it as current. */
    @Column(name = "price_as_of")
    private java.time.LocalDate priceAsOf;

    @Column(length = 40)
    private String broker;   // UPStox | MStock | Kotak | Zerodha | HDFC | SBI …

    @Column(length = 60)
    private String folio;    // MF folio number

    @Column(name = "buy_date")
    private java.time.LocalDate buyDate;   // first purchase date

    @Column(precision = 8, scale = 2)
    private BigDecimal xirr;   // annualised return % (from statement, MFs)

    /**
     * AMFI scheme code for MF holdings (.MF pseudo-symbols), resolved once from {@link #name}
     * by {@code MfSchemeLinkService}. Stays null when no confident match exists — downstream
     * must then show "not linked" rather than another scheme's performance.
     */
    @Column(name = "amfi_scheme_code", length = 20)
    private String amfiSchemeCode;

    /** ISIN — covers both MF schemes and listed equities. Nullable: not every source states it. */
    @Column(length = 20)
    private String isin;

    /** Depository Participant id for a demat (equity) holding. Nullable — stock holdings only. */
    @Column(name = "dp_id", length = 20)
    private String dpId;

    /** Broker client id for a demat (equity) holding. Nullable — stock holdings only. */
    @Column(name = "client_id", length = 20)
    private String clientId;

    @JsonIgnore
    @OneToMany(mappedBy = "holding", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Transaction> transactions = new ArrayList<>();

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    // Computed at query time
    @Transient
    public BigDecimal getInvestedValue() {
        return averageCost.multiply(quantity);
    }

    /** A stock quote this many calendar days old is stale — covers a weekend plus a holiday. */
    public static final int STOCK_STALE_DAYS = 4;
    /** NAVs publish a day late, so a fund gets one more day than a stock. */
    public static final int MF_STALE_DAYS = 5;

    /** How {@link #getCurrentValue()} was arrived at. */
    public enum ValuationBasis {
        /** A price no older than the staleness window. */
        MARKET,
        /** A price exists but is older than the window, or its date is unknown. */
        STALE,
        /** No usable price (missing, or zero) — valued at what was paid, and flagged as such. */
        COST
    }

    /** Sets the price together with the day it is from; a zero or negative price is ignored. */
    public void applyPrice(BigDecimal price, java.time.LocalDate asOf) {
        if (price == null || price.signum() <= 0) return;
        this.currentPrice = price;
        this.priceAsOf = asOf;
    }

    @Transient
    public boolean hasUsablePrice() {
        return currentPrice != null && currentPrice.signum() > 0;
    }

    @Transient
    public ValuationBasis getValuationBasis() {
        return valuationBasis(java.time.LocalDate.now());
    }

    public ValuationBasis valuationBasis(java.time.LocalDate today) {
        if (!hasUsablePrice()) return ValuationBasis.COST;
        if (priceAsOf == null) return ValuationBasis.STALE;
        boolean mf = symbol != null && symbol.toUpperCase().endsWith(".MF");
        int window = mf ? MF_STALE_DAYS : STOCK_STALE_DAYS;
        return priceAsOf.isBefore(today.minusDays(window)) ? ValuationBasis.STALE : ValuationBasis.MARKET;
    }

    /**
     * Quantity × price. With no usable price the position is carried at cost rather than as
     * zero — dropping it would understate net worth by the whole position — but
     * {@link #getValuationBasis()} then reads COST, and every total built from it says so.
     * A zero price is treated as missing: no listed instrument trades at zero, so a zero is a
     * failed quote, not a value.
     */
    @Transient
    public BigDecimal getCurrentValue() {
        return hasUsablePrice() ? currentPrice.multiply(quantity) : getInvestedValue();
    }

    @Transient
    public BigDecimal getPnl() {
        return getCurrentValue().subtract(getInvestedValue());
    }

    @Transient
    public BigDecimal getPnlPercent() {
        BigDecimal invested = getInvestedValue();
        if (invested.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return getPnl().divide(invested, 4, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }
}
