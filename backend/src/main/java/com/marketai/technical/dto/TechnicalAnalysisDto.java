package com.marketai.technical.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class TechnicalAnalysisDto {
    private String symbol;
    private BigDecimal price;

    // RSI
    private BigDecimal rsi;

    // MACD
    private BigDecimal macd;
    private BigDecimal macdSignal;
    private BigDecimal macdHistogram;

    // Moving Averages
    private BigDecimal sma20;
    private BigDecimal sma50;
    private BigDecimal sma200;
    private BigDecimal ema20;

    // Bollinger Bands
    private BigDecimal bollingerUpper;
    private BigDecimal bollingerMiddle;
    private BigDecimal bollingerLower;

    // ATR
    private BigDecimal atr;

    // Support & Resistance
    private BigDecimal support;
    private BigDecimal resistance;

    // Derived signals
    private String trend;          // STRONG_UPTREND, UPTREND, SIDEWAYS, DOWNTREND, STRONG_DOWNTREND, UNKNOWN
    private String signal;         // BUY, SELL, HOLD, INSUFFICIENT_DATA
    private String signalStrength; // STRONG, MODERATE, WEAK

    /**
     * How much real price history these numbers were computed from. Consumers MUST check
     * this before presenting any indicator as fact:
     *   FULL         — >=200 daily bars; every indicator including SMA200 is genuine.
     *   PARTIAL      — >=20 bars but <200; SMA200 (and therefore the long-term trend filter)
     *                  is null/unknown, everything else is genuine.
     *   INSUFFICIENT — <5 bars. No indicator could be computed. Only `price` is real (live
     *                  quote); every other numeric field is null and `signal` is
     *                  INSUFFICIENT_DATA. Never render these as indicator values.
     * Previously this state was silently filled with values derived from a single day's
     * change (RSI = 50 + change%*2, SMA = price, ATR = 1% of price, …) and returned in a DTO
     * indistinguishable from a real one, which then flowed unlabelled into the analyst
     * composite score and the forecast's "built from 200 days of history" claim.
     */
    private String dataQuality;

    /** Number of daily bars actually available in price_history for this symbol. */
    private Integer barsAvailable;
}
