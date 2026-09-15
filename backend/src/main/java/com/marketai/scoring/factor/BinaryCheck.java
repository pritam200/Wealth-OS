package com.marketai.scoring.factor;

/**
 * One pass/fail test with the numbers that decided it.
 *
 * <p>Borrowed from Simply Wall St, whose Snowflake is six binary checks per axis. The reason to
 * prefer this over a weighted z-score as the <em>explanation</em> layer is that a z-score cannot
 * be explained to anyone: "quality 0.62" conveys nothing a user can act on or dispute. "Failed 4
 * of 6 financial-health checks — debt-to-equity is 180% against a 40% threshold" is a reason.
 *
 * <p>Continuous scores still do the ranking. Binary checks do the explaining. This system's
 * standing rule is that every recommendation has a reason, and this is the mechanism that
 * satisfies it.
 *
 * @param actual    the measured value, null when the input was unavailable
 * @param threshold what it was compared against
 */
public record BinaryCheck(String name, boolean passed, String actual,
                          String threshold, String explanation) {

    public static BinaryCheck pass(String name, String actual, String threshold) {
        return new BinaryCheck(name, true, actual, threshold,
            String.format("%s: %s (target %s)", name, actual, threshold));
    }

    public static BinaryCheck fail(String name, String actual, String threshold) {
        return new BinaryCheck(name, false, actual, threshold,
            String.format("%s: %s, against a target of %s", name, actual, threshold));
    }

    /**
     * The input was missing. Counted as a failure, deliberately: treating absent data as a pass
     * would let a company with no disclosed debt figure score as though it had no debt.
     */
    public static BinaryCheck unavailable(String name, String threshold) {
        return new BinaryCheck(name, false, null, threshold,
            String.format("%s: not available, so this check could not pass", name));
    }

    public boolean isUnavailable() { return actual == null; }
}
