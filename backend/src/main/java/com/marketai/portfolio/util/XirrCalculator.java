package com.marketai.portfolio.util;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Real XIRR (extended internal rate of return) from actual dated cash flows — Newton-Raphson
 * on the standard XIRR equation, solving for the annualized rate r such that
 * sum(cashflow_i / (1+r)^(days_i/365)) = 0. Replaces treating a holding's "xirr" as a plain
 * stored number (manually entered or imported, never verified against what the transaction
 * ledger actually implies).
 */
public final class XirrCalculator {

    private XirrCalculator() {}

    public static class CashFlow {
        public final LocalDate date;
        public final BigDecimal amount; // negative = money out (BUY), positive = money in (SELL/current value)
        public CashFlow(LocalDate date, BigDecimal amount) {
            this.date = date;
            this.amount = amount;
        }
    }

    private static final int MAX_ITERATIONS = 100;
    private static final double PRECISION = 1e-7;
    private static final double INITIAL_GUESS = 0.1; // 10%

    /**
     * Returns the annualized rate as a percentage (e.g. 14.5 for 14.5%/year), or null if it
     * can't be solved — fewer than 2 cash flows, all flows the same sign (no real investment
     * return to compute), or Newton's method fails to converge within tolerance.
     */
    public static Double computeXirrPercent(List<CashFlow> flows) {
        if (flows == null || flows.size() < 2) return null;

        boolean hasPositive = false, hasNegative = false;
        for (CashFlow f : flows) {
            if (f.amount.signum() > 0) hasPositive = true;
            if (f.amount.signum() < 0) hasNegative = true;
        }
        if (!hasPositive || !hasNegative) return null;

        LocalDate anchor = flows.get(0).date;
        for (CashFlow f : flows) if (f.date.isBefore(anchor)) anchor = f.date;
        final LocalDate anchorDate = anchor;

        double rate = INITIAL_GUESS;
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            double f = 0, fPrime = 0;
            for (CashFlow cf : flows) {
                double years = ChronoUnit.DAYS.between(anchorDate, cf.date) / 365.0;
                double amount = cf.amount.doubleValue();
                double denom = Math.pow(1 + rate, years);
                if (denom == 0 || Double.isNaN(denom) || Double.isInfinite(denom)) return null;
                f += amount / denom;
                fPrime += -amount * years / Math.pow(1 + rate, years + 1);
            }
            if (Math.abs(fPrime) < 1e-12) return null; // flat derivative — can't step further
            double newRate = rate - f / fPrime;
            if (newRate <= -0.999999) newRate = -0.999999; // rate can't go below -100%
            if (Math.abs(newRate - rate) < PRECISION) {
                rate = newRate;
                break;
            }
            rate = newRate;
            if (i == MAX_ITERATIONS - 1) return null; // never converged
        }

        if (Double.isNaN(rate) || Double.isInfinite(rate)) return null;
        return rate * 100.0;
    }
}
