package com.marketai.analyst.dto;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AnalystAssessment {
    private String symbol;
    private String displayName;
    private double price;
    private String rating;        // BUY | HOLD | SELL
    private String conviction;    // HIGH | MEDIUM | LOW
    private int compositeScore;   // -100..100
    private List<Factor> factorBreakdown; // per-factor score/weight/note — technical, momentum, valuation, sentiment, portfolio risk

    private Fundamentals fundamentals;
    private TechnicalLevels technicals;
    private NewsPulse news;
    private List<String> positives;
    private List<String> risks;
    private String aiNarrative;   // optional (Gemini); null if unavailable
    private String basis;

    // Populated by RecommendationEngine — the single source of truth for the advisor-style
    // action every module (Portfolio/Research/AI Advisor/Watchlist) should show identically.
    private int confidenceScore;  // 0..100, |compositeScore|
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
        private String name;      // Technical | Momentum | Valuation | Sentiment
        private int score;        // -100..100
        private int weightPct;
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
        private int score;              // -100..100
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
    }
}

