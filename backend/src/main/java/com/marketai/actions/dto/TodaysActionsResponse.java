package com.marketai.actions.dto;

import com.marketai.recommendation.dto.PortfolioContext;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * "Given my actual portfolio and today's market conditions, what should I do with my money
 * today?" — one answer, bucketed by action type, every entry backed by the same centralized
 * {@code RecommendationEngine} verdict shown everywhere else in the app.
 *
 * Scope, stated explicitly rather than silently assumed: this page covers securities you
 * already hold. It does not surface brand-new buy ideas outside your portfolio, because doing
 * that responsibly would require sizing a rupee amount against your available cash — this app
 * does not track a cash/bank balance, so any such figure would be invented.
 */
@Data
@Builder
public class TodaysActionsResponse {
    private LocalDateTime generatedAt;
    private String scopeNote;

    private PortfolioContext portfolioContext;

    private List<BuyAction> buy;
    private List<SellReduceAction> sellReduce;
    private List<BookProfitAction> bookProfit;
    private List<HoldAction> hold;
    private List<WatchAction> watch;
    /** Holdings the engine could not analyse (insufficient price/NAV history) — never silently dropped. */
    private List<NotAnalysed> notAnalysed;

    @Data
    @Builder
    public static class BuyAction {
        private String symbol;
        private String name;
        private String assetType; // STOCK | MF
        private BigDecimal currentValue;
        private Double currentPercentOfEquity;
        /** Upper bound only — how much MORE could be added before breaching the single-position
         *  concentration guideline. Not a target amount; there is no cash-balance data to size one. */
        private BigDecimal maxAddWithoutBreachingGuideline;
        private String why;
        private String risk;
        private int confidence;
    }

    @Data
    @Builder
    public static class SellReduceAction {
        private String symbol;
        private String name;
        private String assetType;
        private BigDecimal currentValue;
        private Double pnlPercent;
        private String action; // FULL_EXIT | SWITCH | REDUCE
        private String why;
        private String riskReward;
        /** MF only — null for stocks. Reuses AnalystAssessment.taxImpact verbatim. */
        private String taxImpact;
    }

    @Data
    @Builder
    public static class BookProfitAction {
        private String symbol;
        private String name;
        private String assetType;
        private BigDecimal currentValue;
        private Double pnlPercent;
        private BigDecimal currentProfitAmount;
        private Double suggestedBookPercent;
        private BigDecimal suggestedBookAmount;
        private String why;
        private String taxImpact;
        private ReinvestmentPlan reinvestmentPlan;
    }

    @Data
    @Builder
    public static class ReinvestmentPlan {
        private BigDecimal totalToRedeploy;
        private List<Tranche> tranches;
        private String basis;

        @Data
        @Builder
        public static class Tranche {
            private String label;
            private BigDecimal amount;
            private Double percentOfTotal;
            private String condition;
        }
    }

    @Data
    @Builder
    public static class HoldAction {
        private String symbol;
        private String name;
        private String assetType;
        private BigDecimal currentValue;
        private Double pnlPercent;
        private String why;
    }

    @Data
    @Builder
    public static class WatchAction {
        private String symbol; // null for portfolio-level (non-security) watch items
        private String name;
        private String assetType; // STOCK | MF | PORTFOLIO
        private String condition;
        private String why;
    }

    @Data
    @Builder
    public static class NotAnalysed {
        private String symbol;
        private String name;
        private String assetType;
        private String reason;
    }
}
