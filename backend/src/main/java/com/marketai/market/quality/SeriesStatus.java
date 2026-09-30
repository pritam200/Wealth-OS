package com.marketai.market.quality;

/**
 * Whether a daily price series may be used for analysis, in order of severity.
 * Every analytical output carries one of these so that a number computed from stale or damaged
 * data can never be mistaken for a current one.
 */
public enum SeriesStatus {
    /** Validated, current, nothing notable. */
    OK,
    /** Usable, but something about the data needs to be shown next to the result. */
    DATA_QUALITY_WARNING,
    /** The newest bar is too far behind the last completed session to call current. */
    STALE_DATA,
    /** Too few valid bars for the calculation that was asked for. */
    INSUFFICIENT_DATA
}
