package com.marketai.scoring.factor;

import com.marketai.common.quality.DataQuality;

import java.util.List;

/**
 * One factor's outcome: a score for ranking, and binary checks for explaining.
 *
 * <p>The score is the proportion of checks passed rather than a weighted blend of the underlying
 * ratios. That is a deliberate simplification: a blend requires cross-sectional percentiles to
 * be meaningful, and this codebase has no reliable market-wide fundamentals feed (see the
 * research on Indian data sources — Screener is a scrape, consensus estimates are not free).
 * Claiming a percentile-calibrated score without that data would be inventing precision.
 */
public record FactorScore(String name, double score, List<BinaryCheck> checks,
                          DataQuality quality, String unavailableReason) {

    public static FactorScore of(String name, List<BinaryCheck> checks) {
        long available = checks.stream().filter(c -> !c.isUnavailable()).count();
        long passed = checks.stream().filter(BinaryCheck::passed).count();

        // Missing inputs degrade the stated quality rather than the score. A stock that passes
        // two of two available checks is not as well understood as one that passes four of four,
        // and the caller needs to be able to tell them apart.
        DataQuality q = available == checks.size() ? DataQuality.FULL
            : available == 0 ? DataQuality.INSUFFICIENT
            : DataQuality.PARTIAL;

        return new FactorScore(name, checks.isEmpty() ? 0.0 : (double) passed / checks.size(),
            List.copyOf(checks), q, null);
    }

    public static FactorScore unavailable(String name, String reason) {
        return new FactorScore(name, 0.0, List.of(), DataQuality.INSUFFICIENT, reason);
    }

    public boolean isUsable() { return quality.isUsable(); }

    public List<BinaryCheck> failed() {
        return checks.stream().filter(c -> !c.passed()).toList();
    }

    /** The sentence a user reads instead of a bare number. */
    public String explanation() {
        if (!isUsable()) {
            return name + " could not be assessed: " + unavailableReason;
        }
        long passed = checks.stream().filter(BinaryCheck::passed).count();
        return String.format("%s: passed %d of %d checks%s", name, passed, checks.size(),
            failed().isEmpty() ? "" : " — " + String.join("; ",
                failed().stream().map(BinaryCheck::explanation).toList()));
    }
}
