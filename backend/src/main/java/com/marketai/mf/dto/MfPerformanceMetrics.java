package com.marketai.mf.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * MF performance computed from NAV history we actually hold — nothing here is sourced from a
 * fund factsheet or extrapolated.
 *
 * <p><b>Every metric is nullable and null means "not computable from stored history".</b> A
 * caller must render null as "insufficient data", never as zero. {@link #observationCount},
 * {@link #firstNavDate} and {@link #lastNavDate} state the real basis so a caller can say
 * "3Y CAGR over 742 observations from 2023-08-30" instead of claiming a period it doesn't have.
 */
@Data
@Builder
public class MfPerformanceMetrics {

    private String schemeCode;

    /** Absolute % change over the trailing 1 year. Null if history doesn't span 1 year. */
    private BigDecimal return1Y;

    /** Annualised (CAGR) % over the trailing 3 years. Null if history doesn't span 3 years. */
    private BigDecimal return3YCagr;

    /** Annualised (CAGR) % over the trailing 5 years. Null if history doesn't span 5 years. */
    private BigDecimal return5YCagr;

    /** Stddev of daily NAV returns × √252, as a %. Null when too few observations. */
    private BigDecimal annualisedVolatility;

    /** Largest peak-to-trough decline over the stored history, as a positive %. */
    private BigDecimal maxDrawdownPercent;

    /** Oldest stored NAV date used. Null when no history at all. */
    private LocalDate firstNavDate;

    /** Newest stored NAV date used. Null when no history at all. */
    private LocalDate lastNavDate;

    /** Number of NAV observations the metrics above were computed from. */
    private int observationCount;

    private DataQuality dataQuality;

    public enum DataQuality {
        /** History spans at least 5 years — every trailing window is real. */
        FULL,
        /** At least 1 year but under 5: the longer windows will be null. */
        PARTIAL,
        /** Under 1 year (or nothing) — no trailing return is computable. */
        INSUFFICIENT
    }
}
