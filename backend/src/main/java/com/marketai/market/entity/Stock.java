package com.marketai.market.entity;

import javax.persistence.*;
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

    @Column(precision = 12, scale = 2)
    private BigDecimal debtToEquity;

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
}
