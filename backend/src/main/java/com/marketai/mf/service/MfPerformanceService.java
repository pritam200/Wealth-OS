package com.marketai.mf.service;

import com.marketai.mf.dto.MfPerformanceMetrics;
import com.marketai.mf.entity.MfNavHistory;
import com.marketai.mf.repository.MfNavHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Trailing returns, volatility and drawdown for a mutual fund, computed from the NAV history in
 * {@code mf_nav_history} — never from a factsheet figure and never extrapolated.
 *
 * <p>Design rule: a window we cannot actually cover returns null. Reporting a "5Y return"
 * derived from two years of NAVs would be a fabricated financial value, so each window is
 * gated independently on the real span of stored data.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MfPerformanceService {

    /** Indian MF trading days per year, the standard annualisation factor for daily vol. */
    private static final double TRADING_DAYS_PER_YEAR = 252d;

    /**
     * How far before the requested window start we'll accept the base observation. NAV is not
     * published on weekends/holidays, so the exact anniversary is usually missing and the
     * nearest earlier day is the correct base. Beyond ~2 weeks it means a real hole in the
     * data, and silently using it would quietly stretch the window (a "3Y" number actually
     * measured over 3y+2m). We return null instead.
     */
    private static final int MAX_BASE_GAP_DAYS = 15;

    /**
     * Below this, a stddev of daily returns is too noisy to publish as "annualised volatility".
     * ~6 weeks of trading days.
     */
    private static final int MIN_OBSERVATIONS_FOR_VOLATILITY = 30;

    /**
     * Consecutive observations more than a week apart are not a "daily" return; including one
     * would inflate the daily stddev. Such pairs are dropped from the volatility sample.
     */
    private static final int MAX_DAILY_GAP_DAYS = 7;

    private final MfNavHistoryRepository navHistoryRepository;

    /**
     * Performance for a scheme from stored NAV history.
     *
     * @return metrics with nulls wherever the data doesn't support the figure; never null itself
     */
    @Transactional(readOnly = true)
    public MfPerformanceMetrics getPerformance(String schemeCode) {
        if (schemeCode == null || schemeCode.trim().isEmpty()) {
            return empty(schemeCode);
        }
        List<MfNavHistory> history =
                navHistoryRepository.findBySchemeCodeOrderByDateAsc(schemeCode.trim());
        return computeFrom(schemeCode, history);
    }

    /**
     * Pure computation over an ascending-by-date NAV series. Separated from the repository so
     * the maths is unit-testable without a database.
     */
    public MfPerformanceMetrics computeFrom(String schemeCode, List<MfNavHistory> history) {
        if (history == null || history.isEmpty()) return empty(schemeCode);

        List<MfNavHistory> asc = new ArrayList<>(history);
        asc.sort(Comparator.comparing(MfNavHistory::getDate));

        int n = asc.size();
        LocalDate first = asc.get(0).getDate();
        LocalDate last = asc.get(n - 1).getDate();
        BigDecimal endNav = asc.get(n - 1).getNav();

        MfPerformanceMetrics.DataQuality quality;
        if (!first.isAfter(last.minusYears(5))) {
            quality = MfPerformanceMetrics.DataQuality.FULL;
        } else if (!first.isAfter(last.minusYears(1))) {
            quality = MfPerformanceMetrics.DataQuality.PARTIAL;
        } else {
            quality = MfPerformanceMetrics.DataQuality.INSUFFICIENT;
        }

        return MfPerformanceMetrics.builder()
                .schemeCode(schemeCode)
                .return1Y(trailingReturn(asc, endNav, last, 1, false))
                .return3YCagr(trailingReturn(asc, endNav, last, 3, true))
                .return5YCagr(trailingReturn(asc, endNav, last, 5, true))
                .annualisedVolatility(annualisedVolatility(asc))
                .maxDrawdownPercent(maxDrawdown(asc))
                .firstNavDate(first)
                .lastNavDate(last)
                .observationCount(n)
                .dataQuality(quality)
                .build();
    }

    /**
     * Trailing return over {@code years}, annualised (CAGR) when asked. Null unless the stored
     * history genuinely reaches back that far.
     */
    private BigDecimal trailingReturn(List<MfNavHistory> asc, BigDecimal endNav,
                                      LocalDate last, int years, boolean annualise) {
        LocalDate target = last.minusYears(years);
        LocalDate first = asc.get(0).getDate();

        // The whole point: no data older than the window start means no such return exists.
        if (first.isAfter(target)) return null;

        int idx = indexOfLastOnOrBefore(asc, target);
        if (idx < 0) return null;

        MfNavHistory base = asc.get(idx);
        if (base.getDate().isBefore(target.minusDays(MAX_BASE_GAP_DAYS))) return null;

        BigDecimal startNav = base.getNav();
        if (startNav == null || startNav.compareTo(BigDecimal.ZERO) <= 0) return null;
        if (endNav == null || endNav.compareTo(BigDecimal.ZERO) <= 0) return null;

        double ratio = endNav.doubleValue() / startNav.doubleValue();
        double pct = annualise
                ? (Math.pow(ratio, 1d / years) - 1d) * 100d
                : (ratio - 1d) * 100d;

        if (Double.isNaN(pct) || Double.isInfinite(pct)) return null;
        return BigDecimal.valueOf(pct).setScale(2, RoundingMode.HALF_UP);
    }

    /** Index of the newest observation dated on or before {@code target}; -1 if none. */
    private int indexOfLastOnOrBefore(List<MfNavHistory> asc, LocalDate target) {
        int found = -1;
        for (int i = 0; i < asc.size(); i++) {
            if (asc.get(i).getDate().isAfter(target)) break;
            found = i;
        }
        return found;
    }

    /** Sample stddev of daily NAV returns × √252, as a %. Null when the sample is too thin. */
    private BigDecimal annualisedVolatility(List<MfNavHistory> asc) {
        if (asc.size() < MIN_OBSERVATIONS_FOR_VOLATILITY) return null;

        List<Double> returns = new ArrayList<>();
        for (int i = 1; i < asc.size(); i++) {
            MfNavHistory prev = asc.get(i - 1);
            MfNavHistory cur = asc.get(i);
            if (prev.getNav() == null || cur.getNav() == null) continue;
            double p = prev.getNav().doubleValue();
            if (p <= 0) continue;
            long gap = java.time.temporal.ChronoUnit.DAYS.between(prev.getDate(), cur.getDate());
            if (gap <= 0 || gap > MAX_DAILY_GAP_DAYS) continue;
            returns.add(cur.getNav().doubleValue() / p - 1d);
        }
        if (returns.size() < 2) return null;

        double mean = 0d;
        for (double r : returns) mean += r;
        mean /= returns.size();

        double sumSq = 0d;
        for (double r : returns) sumSq += (r - mean) * (r - mean);
        double sd = Math.sqrt(sumSq / (returns.size() - 1)); // sample stddev

        double vol = sd * Math.sqrt(TRADING_DAYS_PER_YEAR) * 100d;
        if (Double.isNaN(vol) || Double.isInfinite(vol)) return null;
        return BigDecimal.valueOf(vol).setScale(2, RoundingMode.HALF_UP);
    }

    /** Largest peak-to-trough decline over the whole stored series, as a positive %. */
    private BigDecimal maxDrawdown(List<MfNavHistory> asc) {
        if (asc.size() < 2) return null;

        double peak = -1d;
        double worst = 0d;
        for (MfNavHistory h : asc) {
            if (h.getNav() == null) continue;
            double nav = h.getNav().doubleValue();
            if (nav <= 0) continue;
            if (nav > peak) {
                peak = nav;
            } else if (peak > 0) {
                double dd = (peak - nav) / peak;
                if (dd > worst) worst = dd;
            }
        }
        if (peak <= 0) return null;
        return BigDecimal.valueOf(worst * 100d).setScale(2, RoundingMode.HALF_UP);
    }

    private MfPerformanceMetrics empty(String schemeCode) {
        return MfPerformanceMetrics.builder()
                .schemeCode(schemeCode)
                .observationCount(0)
                .dataQuality(MfPerformanceMetrics.DataQuality.INSUFFICIENT)
                .build();
    }
}
