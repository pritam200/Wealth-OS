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

    /** Cash the advisory is allowed to size positions against. */
    private CashPosition cash;

    @Data
    @Builder
    public static class CashPosition {
        /** Authoritative total across tracked bank/cash accounts. */
        private BigDecimal trackedCash;
        /**
         * Of that cash, how much arrived from MF redemptions and hasn't been redeployed.
         * A label on part of trackedCash — never added to it, which would double-count.
         */
        private BigDecimal earmarkedFromRedemptions;
        /** True when at least one cash account exists, i.e. sizing is possible at all. */
        private boolean cashTracked;
        private String note;
    }

    @Data
    @Builder
    public static class BuyAction {
        private String symbol;
        private String name;
        private String assetType; // STOCK | MF
        private BigDecimal currentValue;
        private Double currentPercentOfEquity;
        /** Upper bound from concentration alone — how much MORE could be added before
         *  breaching the single-position guideline, ignoring whether the cash exists. */
        private BigDecimal maxAddWithoutBreachingGuideline;
        /** What to actually deploy: the concentration headroom capped by tracked deployable
         *  cash. Null when no cash accounts are tracked, in which case no amount is invented. */
        private BigDecimal suggestedAmount;
        /** How suggestedAmount was arrived at, or why it is absent. */
        private String sizingBasis;
        private String why;
        private String risk;
        private int confidence;
        /** As-of time of the price/NAV data behind this recommendation. */
        private LocalDateTime dataTimestamp;
        private String expectedOutcome;
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
