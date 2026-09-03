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
        private java.time.LocalDate buyDate;
        private BigDecimal xirr;
    }

    @Data
    @Builder
    public static class AllocationDto {
        private String label;
        private BigDecimal value;
        private BigDecimal percent;
    }
}
