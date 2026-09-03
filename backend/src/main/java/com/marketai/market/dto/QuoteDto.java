package com.marketai.market.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class QuoteDto {
    private String symbol;
    private String name;
    private BigDecimal currentPrice;
    private BigDecimal previousClose;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private Long volume;
    private BigDecimal change;
    private BigDecimal changePercent;
    private BigDecimal weekHigh52;
    private BigDecimal weekLow52;
    private BigDecimal marketCap;
    private BigDecimal pe;
    private String sector;

    // Fundamentals, populated from the cached Stock row. Any of these may legitimately be
    // null when Yahoo does not publish the field for the symbol — consumers must render
    // "unavailable" rather than substituting a default.
    private BigDecimal pb;
    private BigDecimal roe;             // fraction, e.g. 0.184 = 18.4%
    private BigDecimal debtToEquity;
    private BigDecimal revenueGrowth;   // fraction
    private BigDecimal earningsGrowth;  // fraction
    private BigDecimal profitMargin;    // fraction
    private BigDecimal currentRatio;
    private BigDecimal eps;
    private LocalDateTime fundamentalsUpdatedAt;

    private LocalDateTime lastUpdated;
}
