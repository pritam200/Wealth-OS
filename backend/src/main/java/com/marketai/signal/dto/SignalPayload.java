package com.marketai.signal.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Risk-adjusted signal output.
 *
 * Replaces a bare BUY/SELL string. Two properties matter most and are enforced by the engine
 * rather than left to callers:
 *
 *  - A factor that could not be computed is null WITH a reason, never 0. Scoring a missing
 *    input as neutral quietly pulls the composite toward HOLD and misrepresents what was
 *    actually measured.
 *  - `confidenceCeiling` caps the score by how many factor families were available. A read
 *    built from 3 of 5 families cannot claim 100% confluence.
 */
@Data @Builder
public class SignalPayload {

    public enum Type { BUY, SELL, HOLD, NO_ACTIONABLE_SIGNAL, STALE_DATA, INSUFFICIENT_DATA }

    /** What the rule would say before validation; {@link #signal} is NO_ACTIONABLE_SIGNAL unless it has earned it. */
    private Type ruleOutput;

    /** How the rule's calls on this instrument have actually played out. */
    private Validation validation;

    @Data @Builder
    public static class Validation {
        private int horizonSessions;
        private int observations;
        private int buyCalls;
        private int sellCalls;
        /** Share of BUY calls followed by a higher close after the horizon; null with no calls. */
        private Double buyHitRate;
        private Double buyHitRateCiLow;
        private Double sellHitRate;
        private Double sellHitRateCiLow;
        /** Unconditional share of windows that ended higher over the same period. */
        private double baseUpRate;
        private Double meanForwardReturnBuyPct;
        private Double meanForwardReturnSellPct;
        private double meanForwardReturnAllPct;
        /** True when the current call's hit-rate lower bound beats the base rate on ≥30 independent calls. */
        private boolean currentCallValidated;
        private String summary;
    }

    private String symbol;

    /**
     * As-of time of the newest CLOSED bar used — not the request time. Reading a bar that
     * hasn't closed yet is the classic multi-timeframe look-ahead bug.
     */
    private LocalDateTime asOf;

    private BigDecimal priceAtSignal;

    private Type signal;
    private Integer confidence;          // null when INSUFFICIENT_DATA
    private Integer confidenceCeiling;

    private String dataQuality;          // FULL | PARTIAL | INSUFFICIENT
    private List<String> timeframesUsed;
    private List<String> timeframesMissing;

    /** Keyed by family: trend, momentum, marketStructure, volume, volatility, microstructure. */
    private Map<String, Factor> factors;

    private Execution execution;         // null for HOLD / INSUFFICIENT_DATA
    private List<Guardrail> guardrails;
    private List<String> rationale;
    private String engineVersion;

    @Data @Builder
    public static class Factor {
        /** -100..+100, or null when unavailable. */
        private Integer score;
        private double weight;
        private String timeframe;
        private String detail;
        /** Required whenever score is null. */
        private String reason;
    }

    @Data @Builder
    public static class Execution {
        private BigDecimal entryLow;
        private BigDecimal entryHigh;
        private BigDecimal stopLoss;
        private BigDecimal takeProfit;
        private BigDecimal riskRewardRatio;
        private BigDecimal atr;
        private int atrPeriod;
        private BigDecimal atrMultipleStop;
        private BigDecimal atrMultipleTarget;
        private String basis;
        private PositionSizing positionSizing;
    }

    @Data @Builder
    public static class PositionSizing {
        /** Null when no cash balance is tracked — an amount with no funding behind it is invented. */
        private BigDecimal suggestedAmount;
        private BigDecimal riskPerTradePercent;
        private BigDecimal riskAmount;
        private Integer shares;
        private String limitedBy;   // CASH | CONCENTRATION_GUIDELINE | NOT_SIZED
        private String basis;
    }

    @Data @Builder
    public static class Guardrail {
        private String rule;     // LOW_VOLUME_TRAP | COUNTER_TREND | INSUFFICIENT_BARS | STALE_DATA
        private String action;   // SUPPRESSED | DOWNGRADED | FLAGGED
        private String detail;
    }
}
