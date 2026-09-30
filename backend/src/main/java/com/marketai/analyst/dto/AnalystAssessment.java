package com.marketai.analyst.dto;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AnalystAssessment {
    private String symbol;
    private String displayName;
    private double price;
    /**
     * BUY or SELL only when the signal rule's call has a demonstrated edge on this instrument's
     * own history (SignalEngine validation); otherwise NO_ACTIONABLE_SIGNAL, STALE_DATA or
     * INSUFFICIENT_DATA. Never produced from a blend of unvalidated factor scores.
     */
    private String rating;
    /** HIGH | MEDIUM | LOW from the validated call's measured edge over the base rate; null when not rated. */
    private String conviction;
    /** The signal rule's factor agreement (−100..100) — reported only for a validated call; null otherwise. */
    private Integer compositeScore;
    /** Each input, what it read, and whether it contributed to the rating. */
    private List<Factor> factorBreakdown;

    /** The rule's reading before validation, and how its calls on this instrument have played out. */
    private String ruleOutput;
    private com.marketai.signal.dto.SignalPayload.Validation signalValidation;
    private com.marketai.technical.dto.TrendAssessment trendAssessment;
    /** Fundamental figures with unit, period, source and fetch time. */
    private List<FundamentalFact> fundamentalFacts;
    /** Session date and source of the price every technical value was computed from. */
    private java.time.LocalDate priceDate;
    private String priceSource;
    private String seriesStatus;

    private Fundamentals fundamentals;
    private TechnicalLevels technicals;
    private NewsPulse news;
    private List<String> positives;
    private List<String> risks;
    private String basis;

    /**
     * The research engine's final view for this stock, read from stored research (this
     * service never calls a model). Code-generated text only — the research itself, which may
     * refer to a portfolio, is served by /api/research. Null when no research exists for the
     * current session.
     */
    private String researchActionability;  // BUY | SELL | HOLD | NO_ACTIONABLE_SIGNAL | CONFLICTING_EVIDENCE | …
    private String researchReason;
    private String researchLean;           // what the research alone concluded
    private java.time.LocalDateTime researchAt;
    private String researchModel;          // e.g. "ollama:qwen2.5:14b"

    // Populated by RecommendationEngine — the single source of truth for the advisor-style
    // action every module (Portfolio/Research/AI Advisor/Watchlist) should show identically.
    /**
     * Historical hit rate (%) of the current call on this instrument — the measured share of past
     * BUY (or SELL) calls followed by a higher (or lower) close after 20 sessions. Null when there
     * is no validated call. It was |compositeScore| before, which is agreement, not a probability.
     */
    private Integer confidenceScore;
    private String nextAction;    // stock: ACCUMULATE|CONTINUE|BOOK_PROFIT|EXIT|REVIEW|HOLD
                                   // MF: CONTINUE_SIP|INCREASE_SIP|PAUSE_SIP|HOLD|PARTIAL_PROFIT_BOOKING|FULL_REDEMPTION|REBALANCE|SWITCH_FUND
    private String nextActionReason; // plain-English explanation of why THIS action was chosen (not just the composite rating)
    private String taxImpact;     // MF only — e.g. "STCG ~₹12,400 at 15% if redeemed today; LTCG in 47 days". null for stocks.

    /**
     * Truthfulness marker for the underlying inputs — FULL | PARTIAL | INSUFFICIENT.
     * INSUFFICIENT means there was not enough stored price history to compute indicators, so
     * no rating/score here is meaningful and the UI must show "Insufficient data" rather than
     * an action. PARTIAL means <200 daily bars, so the 200-DMA long-term trend filter was
     * unavailable and the trend read is short-horizon only.
     */
    private String dataQuality;
    private Integer barsAvailable; // daily bars of real price history behind this assessment

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Factor {
        private String name;
        /** TECHNICAL_SIGNAL | MARKET_TREND | MOMENTUM | VALUATION | NEWS_SENTIMENT | PORTFOLIO_RISK | HOLDING | BENCHMARK */
        private String category;
        /** −100..100 where the factor has a defined score; null for descriptive readings. */
        private Integer score;
        private int weightPct;
        private String reading;
        private String note;
        /** True only for inputs the rating was actually derived from. */
        private boolean contributesToRating;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FundamentalFact {
        private String name;
        private BigDecimal value;
        private String unit;
        private String period;
        private String source;
        private java.time.LocalDateTime fetchedAt;
        private String note;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Fundamentals {
        private BigDecimal pe;
        private BigDecimal marketCap;
        private BigDecimal weekHigh52;
        private BigDecimal weekLow52;
        private Double pctOf52wRange;   // 0..100 where price sits in the 52w band
        private String sector;
        private String trend;
        private BigDecimal rsi;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TechnicalLevels {
        private BigDecimal sma20;
        private BigDecimal sma50;
        private BigDecimal sma200;
        private BigDecimal support;
        private BigDecimal resistance;
        private BigDecimal macd;
        private BigDecimal macdSignal;
        private BigDecimal atr;
        private BigDecimal bollingerUpper;
        private BigDecimal bollingerLower;
        private BigDecimal dayChangePercent;
        private Long volume;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class NewsPulse {
        /** Always NEWS_SENTIMENT — reported separately, never folded into the rating. */
        private String category;
        /** OK or INSUFFICIENT (fewer than 3 recent, relevant, unique headlines). */
        private String status;
        private Integer score;          // -100..100, null when INSUFFICIENT
        private String label;           // POSITIVE | NEGATIVE | MIXED | NEUTRAL
        private String confidence;      // LOW | MEDIUM
        private int windowDays;
        private int excluded;
        private String method;
        private int positive;
        private int negative;
        private int total;
        private List<String> headlines; // recent, most relevant
        private List<Article> articles; // recent news with links
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Article {
        private String title;
        private String url;
        private String source;
        private String publishedAt;
        private String sentiment;
        private List<String> matchedTerms;
        private String event;
        private Long ageDays;
    }
}

