package com.marketai.portfolio.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import javax.persistence.*;
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
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Holding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal averageCost;

    @Column(precision = 18, scale = 2)
    private BigDecimal currentPrice;

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

    @Transient
    public BigDecimal getCurrentValue() {
        return currentPrice == null ? getInvestedValue() : currentPrice.multiply(quantity);
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
