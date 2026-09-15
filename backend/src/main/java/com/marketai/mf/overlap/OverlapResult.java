package com.marketai.mf.overlap;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Weighted overlap between two portfolios.
 *
 * @param commonHoldings each shared stock and the weight it contributes
 * @param asOf           the date of the underlying disclosures — part of the answer, not metadata
 */
public record OverlapResult(BigDecimal overlapPercent,
                            List<CommonHolding> commonHoldings,
                            LocalDate asOf, int daysStale,
                            String methodology) {

    public record CommonHolding(String isin, String name,
                                BigDecimal weightA, BigDecimal weightB,
                                BigDecimal contribution) {}

    /**
     * Beyond this the disclosures are too old to apply to today's values — a fund that has since
     * exited a stock would still show exposure to it.
     */
    public static final int MAX_USEFUL_STALENESS_DAYS = 60;

    public boolean isStale() { return daysStale > MAX_USEFUL_STALENESS_DAYS; }

    /** The three largest contributors — where the duplication actually is. */
    public List<CommonHolding> topContributors(int n) {
        return commonHoldings.stream()
            .sorted((a, b) -> b.contribution().compareTo(a.contribution()))
            .limit(n).toList();
    }

    public String summary() {
        if (isStale()) {
            return String.format(
                "Overlap not shown: the latest published portfolios are %d days old (as of %s), "
                    + "so applying them to today's holdings would report positions the funds may "
                    + "have already exited.", daysStale, asOf);
        }
        return String.format("%s%% overlap as of %s, across %d shared holdings. %s",
            overlapPercent.setScale(1, RoundingMode.HALF_UP).toPlainString(),
            asOf, commonHoldings.size(), methodology);
    }
}
