package com.marketai.technical.dto;

import com.marketai.market.quality.DataIssue;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The canonical technical read of one symbol, computed once from the validated daily series
 * (MarketDataService.getDailySeries). Every screen and service consumes this rather than
 * recomputing. Any value is null when its definition cannot be met by the available history —
 * never 0, 50 or another placeholder. {@link #indicators} carries formula, period, timeframe,
 * as-of date and sufficiency for each value.
 */
@Data
@Builder
public class TechnicalAnalysisDto {
    private String symbol;
    /** Close of the newest completed session — see {@link #lastBarDate}. Not a live quote. */
    private BigDecimal price;

    private BigDecimal rsi;

    private BigDecimal macd;
    private BigDecimal macdSignal;
    private BigDecimal macdHistogram;

    private BigDecimal sma20;
    private BigDecimal sma50;
    private BigDecimal sma100;
    private BigDecimal sma200;
    private BigDecimal ema20;
    private BigDecimal ema50;
    private BigDecimal ema200;

    private BigDecimal bollingerUpper;
    private BigDecimal bollingerMiddle;
    private BigDecimal bollingerLower;

    /** Wilder ATR(14), in rupees (a range, not a volatility percentage). */
    private BigDecimal atr;
    /** ATR as a percentage of price — still a range measure, roughly 1.3–1.6× the daily σ. */
    private BigDecimal atrPct;

    private BigDecimal adx;
    private BigDecimal plusDi;
    private BigDecimal minusDi;

    /** Sample standard deviation of daily log returns over {@link #volatilityBars} sessions, percent. */
    private BigDecimal dailyVolatilityPct;
    /** dailyVolatilityPct × √252. */
    private BigDecimal annualizedVolatilityPct;
    private Integer volatilityBars;

    private VolumeProfile volume;

    /** Highest high / lowest low of the last 250 stored sessions (fewer if history is shorter — see range52wSessions). */
    private BigDecimal high52w;
    private BigDecimal low52w;
    private Integer range52wSessions;
    /** Where the close sits in that range, 0–100. */
    private BigDecimal rangePosition52wPct;

    /** Nearest support / resistance price (null = NO_RELIABLE_LEVEL); detail in {@link #levels}. */
    private BigDecimal support;
    private BigDecimal resistance;
    private SupportResistance levels;

    /** STRONG_UPTREND, UPTREND, SIDEWAYS, DOWNTREND, STRONG_DOWNTREND, INSUFFICIENT_DATA; evidence in {@link #trendAssessment}. */
    private String trend;
    private TrendAssessment trendAssessment;

    private List<IndicatorValue> indicators;

    /**
     * FULL (≥200 bars), PARTIAL (20–199: no SMA200), INSUFFICIENT (<20: nothing computed).
     * Kept for existing consumers; {@link #seriesStatus} carries freshness and integrity.
     */
    private String dataQuality;
    /** OK, DATA_QUALITY_WARNING, STALE_DATA or INSUFFICIENT_DATA — see {@link #dataIssues}. */
    private String seriesStatus;
    private List<DataIssue> dataIssues;

    private Integer barsAvailable;
    private Integer barsRejected;
    private LocalDate firstBarDate;
    /** Date of the newest bar every value was computed from. */
    private LocalDate lastBarDate;
    /** The last session that should have a bar by now. */
    private LocalDate expectedSession;
    private Boolean stale;
    private String source;
    /** When the newest bar was last written from the provider. */
    private LocalDateTime dataUpdatedAt;
    private String timeframe;
}
