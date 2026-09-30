package com.marketai.forecast.dto;

import com.marketai.forecast.model.ForecastBacktester;
import com.marketai.market.quality.DataIssue;
import lombok.*;

import java.time.LocalDate;
import java.util.List;

/**
 * A model-estimated price range for one horizon, with its method, the data it came from, and
 * how the same model has actually performed on this instrument's own history. It is not a
 * prediction of where the price will go: the model has no directional view.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ForecastResponse {
    private String symbol;
    private String displayName;
    /** Canonical horizon code: 1D, 5D, 10D, 20D, 60D. */
    private String horizon;
    private int tradingDays;

    /** OK, DATA_QUALITY_WARNING, STALE_DATA or INSUFFICIENT_DATA; no range is given for the last two. */
    private String status;
    private String statusReason;

    /** Close the range is measured from, and its session date. */
    private Double currentPrice;
    private LocalDate priceDate;
    private LocalDate horizonEndsAround;

    private Volatility volatility;
    /** Central 50% and 90% ranges of the model distribution. */
    private Range range50;
    private Range range90;
    private List<Scenario> scenarios;

    /** EMPIRICAL when measured from ≥30 independent windows of this instrument's history, else UNAVAILABLE. */
    private String probabilityStatus;
    private String probabilityNote;

    private Calibration calibration;
    private Directional directional;
    private PointErrors pointErrors;

    /** Context from the canonical technical read — not inputs to the range. */
    private String trend;
    private Double rsi;
    private Double sma50;
    private Double sma200;
    private Double support;
    private Double resistance;

    private List<String> keyDrivers;
    private List<String> keyRisks;
    private List<DataIssue> dataIssues;
    private int dataPoints;
    private LocalDate firstBarDate;
    private String source;
    private String methodology;
    private String basis;

    /** The full walk-forward backtest behind the calibration figures. */
    private ForecastBacktester.Result backtest;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Volatility {
        private double dailyPct;
        private double annualizedPct;
        private double horizonPct;
        private int window;
        private String method;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Range {
        private double low;
        private double high;
        private double nominalCoverage;
        /** Share of historical windows that ended inside the equivalent range; null if unmeasured. */
        private Double historicalCoverage;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Scenario {
        private String label;       // Bull | Base | Bear
        private String direction;   // up | flat | down
        /** Band of the model distribution this scenario covers, as quantiles (e.g. 0.75–0.95). */
        private double quantileLow;
        private double quantileHigh;
        private double low;
        private double high;
        /** Representative price: the median of the band. */
        private double reference;
        private double movePctLow;
        private double movePctHigh;
        /** Share of the model distribution in the band (by construction). */
        private double nominalProbability;
        /** Measured share of this instrument's historical outcomes in the band, percent; null when unavailable. */
        private Double probability;
        private Double probabilityCiLow;
        private Double probabilityCiHigh;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Calibration {
        /** WELL_CALIBRATED, APPROXIMATELY_CALIBRATED, POORLY_CALIBRATED or UNMEASURED. */
        private String label;
        private String summary;
        private int observations;
        private int effectiveSample;
        private LocalDate from;
        private LocalDate to;
        private List<ForecastBacktester.CurvePoint> curve;
        /** Probability of ending beyond the 90% range, historically (nominal 10%). */
        private Double tailFrequency;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Directional {
        /** The model's P(up): 0.5 — it makes no directional call. */
        private double modelUpProbability;
        private Double historicalUpFrequency;
        private Double movingAverageBaselineHitRate;
        private Double brierModel;
        private Double brierTrailingFrequency;
        private String summary;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PointErrors {
        private Double maeRandomWalkPct;
        private Double rmseRandomWalkPct;
        private Double maeDriftPct;
        private Double rmseDriftPct;
        private Double maeMaReversionPct;
        private Double rmseMaReversionPct;
        private String summary;
    }
}
