package com.marketai.market.quality;

import java.time.LocalDate;

/**
 * One problem found while validating a price series.
 *
 * @param code     machine-readable kind, e.g. INVALID_OHLC, DUPLICATE_CANDLE, UNFINISHED_CANDLE,
 *                 MISSING_DATES, ABNORMAL_MOVE, POSSIBLE_UNADJUSTED_SPLIT, VOLUME_UNAVAILABLE, STALE
 * @param severity INFO (shown as context) or WARNING (lowers the series status)
 * @param date     the bar it concerns, when there is one
 */
public record DataIssue(String code, String severity, LocalDate date, String detail) {
    public static final String INFO = "INFO";
    public static final String WARNING = "WARNING";

    public boolean isWarning() {
        return WARNING.equals(severity);
    }
}
