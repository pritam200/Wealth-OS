package com.marketai.scoring.eligibility;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The published Value Research exclusion set, applied before any factor is computed.
 *
 * <p>Each exclusion exists because the factor model produces a confident-looking number from
 * data that cannot support one:
 *
 * <ul>
 *   <li><b>Untraded recently</b> — price-based factors read a stale quote as a real one.</li>
 *   <li><b>Under three years of history</b> — growth and momentum need a trend; over a few
 *       quarters they measure noise.</li>
 *   <li><b>Negative net worth / accumulated losses</b> — valuation ratios invert. A negative
 *       book value makes P/B negative, which sorts as "cheap".</li>
 *   <li><b>Bottom 1% by market cap</b> — prices are not reliably formed at that size.</li>
 * </ul>
 */
@Component
public class RatingEligibilityChecker {

    private static final int MIN_HISTORY_YEARS = 3;
    private static final int MAX_DAYS_SINCE_TRADE = 30;

    /**
     * @param daysSinceLastTrade   trading days since the last trade, null if unknown
     * @param yearsOfHistory       years of financial statements available
     * @param netWorth             shareholders' equity — negative disqualifies
     * @param accumulatedLosses    true when losses exceed reserves
     * @param marketCapPercentile  0–100 within the market, null if unknown
     */
    public RatingEligibility check(Integer daysSinceLastTrade, Double yearsOfHistory,
                                   BigDecimal netWorth, boolean accumulatedLosses,
                                   Double marketCapPercentile) {
        List<String> exclusions = new ArrayList<>();

        if (daysSinceLastTrade != null && daysSinceLastTrade > MAX_DAYS_SINCE_TRADE) {
            exclusions.add(String.format(
                "not traded in %d days — price-based factors would read a stale quote as current",
                daysSinceLastTrade));
        }
        if (yearsOfHistory != null && yearsOfHistory < MIN_HISTORY_YEARS) {
            exclusions.add(String.format(
                "only %.1f years of financial history — growth and momentum would measure noise",
                yearsOfHistory));
        }
        if (netWorth != null && netWorth.signum() < 0) {
            exclusions.add("negative net worth — valuation ratios invert, so the stock would "
                + "score as cheap precisely because its book value is negative");
        }
        if (accumulatedLosses) {
            exclusions.add("accumulated losses exceed reserves");
        }
        if (marketCapPercentile != null && marketCapPercentile < 1.0) {
            exclusions.add("in the smallest 1% by market cap — prices are not reliably formed "
                + "at that size");
        }

        return exclusions.isEmpty()
            ? RatingEligibility.permit()
            : RatingEligibility.deny(exclusions);
    }
}
