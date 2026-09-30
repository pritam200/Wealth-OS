package com.marketai.market.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "stocks", indexes = {
        @Index(name = "idx_stock_symbol", columnList = "symbol"),
        @Index(name = "idx_stock_exchange", columnList = "exchange")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Stock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String symbol;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 10)
    @Builder.Default
    private String exchange = "NSE";

    @Column(length = 50)
    private String sector;

    @Column(length = 50)
    private String industry;

    @Column(precision = 18, scale = 2)
    private BigDecimal currentPrice;

    @Column(precision = 18, scale = 2)
    private BigDecimal openPrice;

    @Column(precision = 18, scale = 2)
    private BigDecimal highPrice;

    @Column(precision = 18, scale = 2)
    private BigDecimal lowPrice;

    @Column(precision = 18, scale = 2)
    private BigDecimal previousClose;

    @Column(precision = 18, scale = 2)
    private BigDecimal change;

    @Column(precision = 8, scale = 4)
    private BigDecimal changePercent;

    private Long volume;

    @Column(precision = 20, scale = 2)
    private BigDecimal marketCap;

    @Column(precision = 8, scale = 2)
    private BigDecimal pe;

    @Column(precision = 8, scale = 2)
    private BigDecimal pb;

    @Column(precision = 8, scale = 4)
    private BigDecimal dividendYield;

    // Fundamentals sourced from Yahoo quoteSummary (financialData / defaultKeyStatistics).
    // ALL nullable: Yahoo omits these for many Indian tickers. null = unavailable, not zero.
    /** Return on equity as a fraction, e.g. 0.184000 = 18.4%. */
    @Column(precision = 12, scale = 6)
    private BigDecimal roe;

    /**
     * Total debt ÷ equity as a ratio (0.45 = 45%) — from fundamentals version 2 on. Yahoo
     * publishes this as a percentage (45.3), and it used to be stored that way; read it through
     * {@link #debtToEquityRatio()}, which converts rows still on version 1.
     */
    @Column(precision = 12, scale = 4)
    private BigDecimal debtToEquity;

    /** Unit version of the fundamentals block: null/1 = D/E stored as a percentage, 2 = as a ratio. */
    @Column(name = "fundamentals_version")
    private Integer fundamentalsVersion;

    /** Revenue growth as a fraction. */
    @Column(precision = 12, scale = 6)
    private BigDecimal revenueGrowth;

    /** Earnings growth as a fraction. */
    @Column(precision = 12, scale = 6)
    private BigDecimal earningsGrowth;

    /** Net profit margin as a fraction. */
    @Column(precision = 12, scale = 6)
    private BigDecimal profitMargin;

    @Column(precision = 12, scale = 2)
    private BigDecimal currentRatio;

    @Column(precision = 12, scale = 2)
    private BigDecimal eps;

    // Absolute figures in the reporting currency (₹ for NSE listings), Yahoo financialData,
    // trailing twelve months unless noted. Nullable — Yahoo omits many for Indian names.
    @Column(precision = 22, scale = 2) private BigDecimal totalRevenue;
    @Column(precision = 22, scale = 2) private BigDecimal ebitda;
    @Column(precision = 22, scale = 2) private BigDecimal operatingCashflow;
    @Column(precision = 22, scale = 2) private BigDecimal freeCashflow;
    @Column(precision = 22, scale = 2) private BigDecimal totalDebt;
    @Column(precision = 22, scale = 2) private BigDecimal totalCash;
    /** Fractions, e.g. 0.21 = 21%. */
    @Column(precision = 12, scale = 6) private BigDecimal grossMargin;
    @Column(precision = 12, scale = 6) private BigDecimal operatingMargin;
    /** Period end of the latest reported quarter and fiscal year, as Yahoo reports them. */
    private java.time.LocalDate mostRecentQuarter;
    private java.time.LocalDate lastFiscalYearEnd;
    @Column(length = 8) private String financialCurrency;

    /** When the fundamentals block above was last successfully fetched — drives the refresh
     *  guard in MarketDataService. Set on every successful fetch, even a partial one. */
    private LocalDateTime fundamentalsUpdatedAt;

    @Column(precision = 18, scale = 2)
    private BigDecimal weekHigh52;

    @Column(precision = 18, scale = 2)
    private BigDecimal weekLow52;

    private LocalDateTime lastUpdated;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    /** 2: D/E stored as a ratio. 3: adds the absolute-figure block below (revenue, cash flow …). */
    public static final int FUNDAMENTALS_VERSION = 3;
    private static final int DE_RATIO_VERSION = 2;

    /** Debt-to-equity as a ratio, whichever unit the row was stored in. */
    public BigDecimal debtToEquityRatio() {
        if (debtToEquity == null) return null;
        return fundamentalsVersion != null && fundamentalsVersion >= DE_RATIO_VERSION
                ? debtToEquity
                : debtToEquity.divide(BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP);
    }
}
