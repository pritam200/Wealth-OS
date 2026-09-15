package com.marketai.card.counter;

/**
 * The clock a cap or milestone runs on.
 *
 * <p>These are genuinely different and conflating them is a silent off-by-days bug on every cap.
 * SBI measures against the statement cycle; HDFC measures against the calendar month. A counter
 * that assumes one while the issuer uses the other resets on the wrong day, which shows up as a
 * recommendation to use a card whose cap is actually already full.
 */
public enum SpendWindow {
    CALENDAR_MONTH,
    STATEMENT_CYCLE,
    ANNIVERSARY_YEAR,
    FINANCIAL_YEAR,
    QUARTER
}
