package com.marketai.networth.attribution;

/**
 * Why net worth moved.
 *
 * <p>The categories are chosen so that every rupee of change lands in exactly one of them and
 * the total reconciles. That exhaustiveness is the point: a category set that does not close
 * forces a residual, and a residual silently absorbs every bug in every other component.
 */
public enum AttributionKind {

    /** Money added from outside — salary saved, a deposit, a gift received. */
    CONTRIBUTION,

    /** Money taken out — a withdrawal to spend elsewhere. */
    WITHDRAWAL,

    /** Money spent. Reduces net worth. */
    EXPENSE,

    /** Money earned into the tracked accounts — salary, interest, dividends, rent. */
    INCOME,

    /**
     * Market movement on assets already held. <b>Computed from price deltas, never inferred as
     * the leftover.</b>
     */
    REVALUATION,

    /** Brokerage, expense ratios, account charges. */
    FEE,

    /** Tax paid or deducted. */
    TAX;

    /** Whether this category increases net worth when its amount is positive. */
    public boolean isInflow() {
        return this == CONTRIBUTION || this == INCOME || this == REVALUATION;
    }

    /**
     * Whether this is money the user themselves moved, as opposed to something the market did
     * to them. The distinction is the entire point of the feature: "you saved ₹40,000" and
     * "the market gave you ₹40,000" are different facts about the same ₹40,000 increase.
     */
    public boolean isBehavioural() {
        return this != REVALUATION;
    }
}
