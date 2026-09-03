package com.marketai.market.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class MarketOverviewDto {
    private IndexQuote nifty50;
    private IndexQuote bankNifty;
    private IndexQuote sensex;
    private IndexQuote niftyMidcap;
    private List<SectorPerformance> sectors;
    private MarketBreadth breadth;
    private LocalDateTime lastUpdated;

    @Data
    @Builder
    public static class IndexQuote {
        private String symbol;
        private String name;
        private BigDecimal value;
        private BigDecimal change;
        private BigDecimal changePercent;
        private BigDecimal open;
        private BigDecimal high;
        private BigDecimal low;
    }

    @Data
    @Builder
    public static class SectorPerformance {
        private String sector;
        private BigDecimal changePercent;
        private String trend;
    }

    /**
     * Sector index performance versus Nifty over a lookback window, computed from stored
     * price history.
     *
     * All three return figures are nullable on purpose: if we do not have enough stored bars
     * for the sector index or for Nifty, the value is null and {@code available} is false.
     * A null is "we don't know", which is NOT the same as a 0.00 (genuinely flat, or exactly
     * tracking the benchmark) — callers must branch on {@code available}, not on the number.
     */
    @Data
    @Builder
    public static class SectorRelativeStrength {
        private String sector;
        /** The sector index ticker the numbers were computed from, e.g. "^CNXIT". */
        private String symbol;
        private int lookbackDays;
        /** Sector index return % over the window. Null when history is insufficient. */
        private BigDecimal sectorReturnPercent;
        /** Nifty (^NSEI) return % over the same window. Null when history is insufficient. */
        private BigDecimal niftyReturnPercent;
        /** sectorReturnPercent - niftyReturnPercent. Null unless both sides are known. */
        private BigDecimal relativeStrength;
        /** False when any figure above is null, i.e. the sector's strength is unknown. */
        private boolean available;
        /** Why it is unavailable, for surfacing to the user. Null when available. */
        private String unavailableReason;
        /** Actual number of stored bars used for the sector index in this window. */
        private int barsUsed;
        /** Actual number of stored bars used for the Nifty benchmark in this window. */
        private int benchmarkBarsUsed;
    }

    @Data
    @Builder
    public static class MarketBreadth {
        private int advances;
        private int declines;
        private int unchanged;
        private BigDecimal advanceDeclineRatio;
    }
}
