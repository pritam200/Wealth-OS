package com.marketai.document.identity;

/** How an incoming event was matched, in descending order of certainty. */
public enum MatchOutcome {

    /**
     * A globally-unique rail reference matched. The strongest possible evidence — the rail
     * issued that identifier for exactly one payment, so no tolerance or scoring is involved.
     */
    EXACT_REFERENCE,

    /** Every field of the composite natural key agreed exactly. */
    COMPOSITE_KEY,

    /** The composite key agreed within tolerance — amounts to the paise, dates by a few days. */
    TOLERANT,

    /** Similar enough to be worth a human's attention, not similar enough to act on. */
    NEEDS_REVIEW,

    /** No sufficiently similar event exists. This is a new transaction. */
    NEW;

    /** Whether this outcome means "already recorded" and the incoming event must not be booked. */
    public boolean isDuplicate() {
        return this == EXACT_REFERENCE || this == COMPOSITE_KEY || this == TOLERANT;
    }

    /**
     * Whether a human should see this before it is acted on. {@link #TOLERANT} is treated as a
     * duplicate <em>and</em> flagged: acting on it is right often enough to automate, but the
     * decision rested on a tolerance window rather than on evidence, so it should be visible.
     */
    public boolean warrantsFlag() {
        return this == TOLERANT || this == NEEDS_REVIEW;
    }
}
