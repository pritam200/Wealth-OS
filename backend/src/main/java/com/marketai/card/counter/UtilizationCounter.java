package com.marketai.card.counter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * How much of a cap or milestone has been consumed in the current window.
 *
 * <p>The card research concluded that the reward problem is stateful rather than a rate lookup,
 * and this is the state. Four counters run on four different clocks: category caps, milestone
 * progress, fee-waiver progress, and transaction-count milestones. Without them a ranking is
 * wrong the moment any cap fills — and it fills silently, because nothing in a rate table
 * changes.
 *
 * @param source whether the figure is derived from imported transactions or confirmed by the
 *               user. Derived counters can be incomplete when some spend never reached the
 *               ledger, so a recommendation resting on one should say so.
 */
public record UtilizationCounter(String cardId, String capId, SpendWindow window,
                                 SpendPurpose purpose,
                                 LocalDate windowStart, LocalDate windowEnd,
                                 BigDecimal consumed, BigDecimal limit,
                                 Source source) {

    public enum Source { DERIVED, USER_CONFIRMED }

    /** Remaining headroom, never negative. */
    public BigDecimal remaining() {
        if (limit == null) return null;
        BigDecimal left = limit.subtract(consumed == null ? BigDecimal.ZERO : consumed);
        return left.signum() < 0 ? BigDecimal.ZERO : left;
    }

    public boolean isExhausted() {
        BigDecimal left = remaining();
        return left != null && left.signum() == 0;
    }

    public boolean covers(LocalDate date) {
        return date != null && !date.isBefore(windowStart) && !date.isAfter(windowEnd);
    }

    /**
     * How much of {@code spend} would still earn at the accelerated rate before the cap bites.
     * Spend beyond this earns the base rate, which is what makes marginal value differ from
     * average value.
     */
    public BigDecimal acceleratedPortion(BigDecimal spend) {
        if (spend == null) return BigDecimal.ZERO;
        BigDecimal left = remaining();
        if (left == null) return spend;
        return spend.min(left);
    }
}
