package com.marketai.mf.service;

import com.marketai.mf.dto.MfPerformanceMetrics;
import com.marketai.mf.entity.MfNavHistory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The point of these tests is the *nullability* contract as much as the arithmetic: a window
 * the stored history doesn't cover must come back null, never an extrapolated number.
 */
class MfPerformanceServiceTest {

    private static final LocalDate END = LocalDate.of(2026, 8, 31);

    private MfPerformanceService service;

    @BeforeEach
    void setup() {
        service = new MfPerformanceService(null); // computeFrom() does no I/O
    }

    /** Daily series from {@code start} to {@code END} compounding smoothly startNav -> endNav. */
    private List<MfNavHistory> series(LocalDate start, double startNav, double endNav) {
        List<MfNavHistory> out = new ArrayList<>();
        long total = java.time.temporal.ChronoUnit.DAYS.between(start, END);
        double growth = endNav / startNav;
        for (long d = 0; d <= total; d++) {
            double nav = startNav * Math.pow(growth, (double) d / total);
            out.add(MfNavHistory.builder()
                    .schemeCode("119598")
                    .date(start.plusDays(d))
                    .nav(BigDecimal.valueOf(nav))
                    .build());
        }
        return out;
    }

    private List<MfNavHistory> points(LocalDate start, double... navs) {
        List<MfNavHistory> out = new ArrayList<>();
        for (int i = 0; i < navs.length; i++) {
            out.add(MfNavHistory.builder()
                    .schemeCode("119598")
                    .date(start.plusDays(i))
                    .nav(BigDecimal.valueOf(navs[i]))
                    .build());
        }
        return out;
    }

    @Test
    @DisplayName("5Y return is NULL when only 2 years of NAV history exists")
    void fiveYearReturnNullOnTwoYearsOfHistory() {
        MfPerformanceMetrics m = service.computeFrom("119598", series(END.minusYears(2), 100d, 150d));

        assertThat(m.getReturn5YCagr()).isNull();
        assertThat(m.getReturn3YCagr()).isNull();
        assertThat(m.getReturn1Y()).isNotNull();
        assertThat(m.getDataQuality()).isEqualTo(MfPerformanceMetrics.DataQuality.PARTIAL);
        assertThat(m.getFirstNavDate()).isEqualTo(END.minusYears(2));
        assertThat(m.getLastNavDate()).isEqualTo(END);
    }

    @Test
    @DisplayName("Under a year of history: no trailing return at all, quality INSUFFICIENT")
    void underOneYearGivesNoReturns() {
        MfPerformanceMetrics m = service.computeFrom("119598", series(END.minusMonths(6), 100d, 110d));

        assertThat(m.getReturn1Y()).isNull();
        assertThat(m.getReturn3YCagr()).isNull();
        assertThat(m.getReturn5YCagr()).isNull();
        assertThat(m.getDataQuality()).isEqualTo(MfPerformanceMetrics.DataQuality.INSUFFICIENT);
        assertThat(m.getObservationCount()).isGreaterThan(100);
    }

    @Test
    @DisplayName("3Y and 5Y are annualised (CAGR), not the absolute gain")
    void threeAndFiveYearAreAnnualised() {
        // Doubling over 5 years is a 100% absolute gain but a ~14.87% CAGR.
        MfPerformanceMetrics m = service.computeFrom("119598", series(END.minusYears(5), 100d, 200d));

        assertThat(m.getReturn5YCagr().doubleValue()).isCloseTo(14.87, within(0.15));
        // Same constant compounding rate, so the 3Y window annualises to the same figure.
        assertThat(m.getReturn3YCagr().doubleValue()).isCloseTo(14.87, within(0.20));
        assertThat(m.getDataQuality()).isEqualTo(MfPerformanceMetrics.DataQuality.FULL);
    }

    @Test
    @DisplayName("1Y return is absolute, not annualised")
    void oneYearIsAbsolute() {
        MfPerformanceMetrics m = service.computeFrom("119598", series(END.minusYears(1), 100d, 115d));

        assertThat(m.getReturn1Y().doubleValue()).isCloseTo(15.00, within(0.05));
    }

    @Test
    @DisplayName("Max drawdown is the worst peak-to-trough decline")
    void maxDrawdown() {
        // peak 120 -> trough 60 is a 50% drawdown; the later recovery to 90 doesn't reduce it.
        MfPerformanceMetrics m = service.computeFrom("119598", points(END.minusDays(3), 100d, 120d, 60d, 90d));

        assertThat(m.getMaxDrawdownPercent().doubleValue()).isCloseTo(50.00, within(0.01));
        assertThat(m.getObservationCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("A monotonically rising series has zero drawdown and zero volatility")
    void steadyGrowthHasNoDrawdownOrVolatility() {
        MfPerformanceMetrics m = service.computeFrom("119598", series(END.minusYears(5), 100d, 200d));

        assertThat(m.getMaxDrawdownPercent().doubleValue()).isCloseTo(0.00, within(0.01));
        assertThat(m.getAnnualisedVolatility().doubleValue()).isCloseTo(0.00, within(0.01));
    }

    @Test
    @DisplayName("Volatility is null when there are too few observations to measure it")
    void volatilityNullOnThinData() {
        MfPerformanceMetrics m = service.computeFrom("119598", points(END.minusDays(4), 100d, 101d, 99d, 102d, 100d));

        assertThat(m.getAnnualisedVolatility()).isNull();
        assertThat(m.getMaxDrawdownPercent()).isNotNull();
    }

    @Test
    @DisplayName("Empty history reports zero observations and no metrics, not zeroes")
    void emptyHistory() {
        MfPerformanceMetrics m = service.computeFrom("119598", Collections.<MfNavHistory>emptyList());

        assertThat(m.getObservationCount()).isZero();
        assertThat(m.getReturn1Y()).isNull();
        assertThat(m.getReturn3YCagr()).isNull();
        assertThat(m.getReturn5YCagr()).isNull();
        assertThat(m.getAnnualisedVolatility()).isNull();
        assertThat(m.getMaxDrawdownPercent()).isNull();
        assertThat(m.getFirstNavDate()).isNull();
        assertThat(m.getLastNavDate()).isNull();
        assertThat(m.getDataQuality()).isEqualTo(MfPerformanceMetrics.DataQuality.INSUFFICIENT);
    }

    @Test
    @DisplayName("A hole in the data voids the window rather than silently stretching it")
    void gapAtWindowStartVoidsTheWindow() {
        // History reaches back past 1Y, but the nearest observation to the 1Y mark is 60 days
        // too early — using it would report a 14-month change as a "1 year return".
        List<MfNavHistory> withGap = new ArrayList<>(Arrays.asList(
                MfNavHistory.builder().schemeCode("119598")
                        .date(END.minusYears(1).minusDays(60)).nav(BigDecimal.valueOf(100)).build()));
        withGap.addAll(series(END.minusDays(30), 120d, 130d));

        MfPerformanceMetrics m = service.computeFrom("119598", withGap);

        assertThat(m.getReturn1Y()).isNull();
    }
}
