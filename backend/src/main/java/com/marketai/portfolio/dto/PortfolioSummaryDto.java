package com.marketai.portfolio.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class PortfolioSummaryDto {
    private Long portfolioId;
    private String name;
    private BigDecimal totalInvested;
    private BigDecimal currentValue;
    private BigDecimal totalPnl;
    private BigDecimal totalPnlPercent;
    private BigDecimal dayChange;
    private BigDecimal dayChangePercent;

    // Risk metrics
    private BigDecimal beta;
    private BigDecimal sharpeRatio;
    private BigDecimal volatility;
    private BigDecimal maxDrawdown;
    private BigDecimal cagr;

    /** The same totals split into direct stocks and mutual funds, so the Stocks and MF screens
     *  show the backend's figure instead of re-adding holdings in the browser. */
    private BigDecimal stocksInvested;
    private BigDecimal stocksCurrentValue;
    private BigDecimal mfInvested;
    private BigDecimal mfCurrentValue;

    /** Holdings with no usable price, carried at cost, and the value so carried. */
    private int holdingsAtCost;
    private BigDecimal valueAtCost;
    /** Holdings whose price is older than the staleness window. */
    private int holdingsStale;
    private BigDecimal valueStale;

    private List<HoldingDto> holdings;
    private List<AllocationDto> allocation;
    private LocalDateTime lastUpdated;

    @Data
    @Builder
    public static class HoldingDto {
        private Long id;
        private Long portfolioId;
        private String symbol;
        private String name;
        private BigDecimal quantity;
        private BigDecimal averageCost;
        private BigDecimal currentPrice;
        private BigDecimal investedValue;
        private BigDecimal currentValue;
        private BigDecimal pnl;
        private BigDecimal pnlPercent;
        private BigDecimal weightPercent;
        private String broker;
        private String folio;
        private String isin;
        private String dpId;
        private String clientId;
        private java.time.LocalDate buyDate;
        private BigDecimal xirr;
        /** Day the price is from; null when unknown. */
        private java.time.LocalDate priceAsOf;
        /** MARKET | STALE | COST — see {@code Holding.ValuationBasis}. */
        private String valuationBasis;
        /**
         * Additive, read-only overlay from the canonical ledger; null when the user has no ledger or the
         * holding is not in it. VERIFIED | UNVERIFIED | NEEDS_RECONCILIATION. The quantity above is never
         * replaced by the institution's; it is shown beside it so a difference is visible.
         */
        private String verificationState;
        private String verificationSource;
        private java.time.LocalDateTime lastVerifiedAt;
        private BigDecimal institutionQuantity;
    }

    @Data
    @Builder
    public static class AllocationDto {
        private String label;
        private BigDecimal value;
        private BigDecimal percent;
    }
}
