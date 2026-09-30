package com.marketai.technical.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Trend label with the evidence it was derived from. Descriptive of the current state only:
 * the backtest found no measurable edge from the label on forward returns, so it is never
 * turned into a probability.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TrendAssessment {
    /** STRONG_UPTREND, UPTREND, SIDEWAYS, DOWNTREND, STRONG_DOWNTREND, INSUFFICIENT_DATA */
    private String label;
    private int bullishVotes;
    private int bearishVotes;
    private int votesAvailable;
    /** Direction evidence (votes) and confirmation evidence (context only). */
    private List<Evidence> evidence;
    private String rule;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Evidence {
        private String name;
        /** BULLISH, BEARISH, NEUTRAL or UNAVAILABLE */
        private String reading;
        private String detail;
        /** True when it counts as a direction vote; false for confirmation-only evidence (ADX, RSI, volume). */
        private boolean vote;
    }
}
