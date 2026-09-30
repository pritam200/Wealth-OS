package com.marketai.forecast;

import com.marketai.forecast.model.ForecastBacktester;
import com.marketai.forecast.model.Stats;
import com.marketai.forecast.model.VolatilityModel;
import com.marketai.market.entity.PriceHistory;
import com.marketai.support.Bars;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The range model and its walk-forward backtest. */
class ForecastModelTest {

    private static final LocalDate END = LocalDate.of(2026, 9, 29);

    @Test
    @DisplayName("Inverse normal matches standard quantiles")
    void normInv() {
        assertThat(Stats.normInv(0.5)).isCloseTo(0, within(1e-9));
        assertThat(Stats.normInv(0.975)).isCloseTo(1.959963985, within(1e-8));
        assertThat(Stats.normInv(0.25)).isCloseTo(-0.6744897502, within(1e-8));
        assertThat(Stats.normInv(0.05)).isCloseTo(-1.644853627, within(1e-8));
    }

    @Test
    @DisplayName("Volatility conversions are explicit: annual = daily·√252, horizon = daily·√h")
    void conversions() {
        assertThat(VolatilityModel.annualised(0.01)).isCloseTo(0.01 * Math.sqrt(252), within(1e-12));
        assertThat(VolatilityModel.horizonSigma(0.01, 20)).isCloseTo(0.01 * Math.sqrt(20), within(1e-12));
        assertThat(VolatilityModel.quantilePrice(100, 0.1, 0.5)).isCloseTo(100, within(1e-9));
    }

    @Test
    @DisplayName("On a random walk with known σ, the model's ranges are calibrated at every horizon")
    void calibratedOnRandomWalk() {
        List<PriceHistory> bars = Bars.randomWalk(6000, 0.012, 42, END);
        List<LocalDate> dates = bars.stream().map(PriceHistory::getDate).toList();
        for (int h : new int[]{1, 5, 20}) {
            ForecastBacktester.Result r = ForecastBacktester.run("RW", dates, Bars.closes(bars), h);
            assertThat(r.getRange50Coverage()).as("50%% range, h=%d", h).isCloseTo(0.50, within(0.04));
            assertThat(r.getRange90Coverage()).as("90%% range, h=%d", h).isCloseTo(0.90, within(0.03));
            for (int i = 0; i < 5; i++) {
                assertThat(r.getRegionFrequency()[i]).isCloseTo(ForecastBacktester.NOMINAL[i], within(0.04));
            }
            // No directional edge exists in a random walk, and the baseline must not claim one.
            assertThat(r.getMaBaselineHitRate()).isCloseTo(0.5, within(0.05));
            assertThat(r.getBrierModel()).isCloseTo(0.25, within(1e-9));
        }
    }

    @Test
    @DisplayName("No look-ahead: σ at session t is unchanged when every later price is rewritten")
    void noLookAhead() {
        List<Double> closes = Bars.closes(Bars.randomWalk(600, 0.015, 3, END));
        List<Double> altered = new ArrayList<>(closes);
        int k = 400;
        for (int i = k + 1; i < altered.size(); i++) altered.set(i, altered.get(i) * (1 + 0.3 * Math.sin(i)));
        double[] a = ForecastBacktester.dailySigmas(closes), b = ForecastBacktester.dailySigmas(altered);
        for (int t = VolatilityModel.WINDOW; t <= k; t++) assertThat(b[t]).isEqualTo(a[t]);
        assertThat(b[k + 5]).isNotEqualTo(a[k + 5]);
        // The live model at the last bar is the same σ the backtest uses there.
        assertThat(VolatilityModel.dailySigma(closes.subList(0, k + 1))).isCloseTo(a[k], within(1e-12));
    }

    @Test
    @DisplayName("Overlapping windows: the effective sample is observations ÷ horizon")
    void effectiveSample() {
        List<PriceHistory> bars = Bars.randomWalk(400, 0.01, 5, END);
        ForecastBacktester.Result r = ForecastBacktester.run("RW", bars.stream().map(PriceHistory::getDate).toList(), Bars.closes(bars), 20);
        assertThat(r.getEffectiveSample()).isEqualTo(r.getObservations() / 20);
        assertThat(r.getEffectiveSample()).isLessThan(ForecastBacktester.MIN_EFFECTIVE_SAMPLE);
    }

    @Test
    @DisplayName("Too short a history returns no backtest rather than a result from a handful of windows")
    void tooShort() {
        List<PriceHistory> bars = Bars.randomWalk(130, 0.01, 5, END);
        assertThat(ForecastBacktester.run("RW", bars.stream().map(PriceHistory::getDate).toList(), Bars.closes(bars), 20)).isNull();
    }
}
