package com.marketai.forecast.dto;

import lombok.*;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ForecastResponse {
    private String symbol;
    private String displayName;
    private String horizon;        // 1W | 2W | 4W
    private double currentPrice;

    private List<Scenario> scenarios;

    // reference levels the forecast is built on (so the user can see the basis)
    private double atr;            // 14-day average true range (daily volatility proxy)
    private double expectedMove;   // ATR * sqrt(tradingDays) — 1σ move over the horizon
    private double rsi;
    private double sma50;
    private double sma200;
    private double support;
    private double resistance;
    private String trend;          // STRONG_UPTREND ... STRONG_DOWNTREND
    private String signal;         // BUY | HOLD | SELL
    private int dataPoints;        // how many historical bars fed the model
    private String basis;          // human-readable explanation of method + references

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Scenario {
        private String label;       // Bull | Base | Bear
        private String direction;   // up | flat | down
        private int probability;    // %
        private double low;
        private double high;
        private double movePctLow;
        private double movePctHigh;
    }
}
