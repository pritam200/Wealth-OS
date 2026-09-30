package com.marketai.technical;

import com.marketai.market.entity.PriceHistory;
import com.marketai.support.Bars;
import com.marketai.technical.service.Indicators;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Every indicator against an independent reference implementation, on 450 real RELIANCE.NS
 * daily bars (13 Dec 2024 – 30 Sep 2026, Yahoo chart API). The expected values were computed by
 * a separate Python implementation written from the published definitions (Wilder 1978 for
 * RSI/ATR/ADX; SMA-seeded EMA; population-σ Bollinger; sample-σ log returns), not from this code.
 */
class IndicatorReferenceTest {

    private static final List<PriceHistory> BARS = Bars.fixture("reliance_daily.csv");
    private static final List<Double> C = Bars.closes(BARS);

    private static void near(Double actual, double expected) {
        assertThat(actual).isNotNull();
        assertThat(actual).isCloseTo(expected, within(Math.abs(expected) * 1e-9 + 1e-9));
    }

    @Test
    @DisplayName("Wilder RSI(14)")
    void rsi() { near(Indicators.rsi(C, 14), 31.59674768864747); }

    @Test
    @DisplayName("SMA 20/50/100/200 use the most recent bars")
    void sma() {
        near(Indicators.sma(C, 20), 1249.5700000000002);
        near(Indicators.sma(C, 50), 1281.882);
        near(Indicators.sma(C, 100), 1297.701);
        near(Indicators.sma(C, 200), 1361.8410000000001);
    }

    @Test
    @DisplayName("EMA 20/50/200, SMA-seeded")
    void ema() {
        near(Indicators.ema(C, 20), 1240.4477215794545);
        near(Indicators.ema(C, 50), 1271.877231631143);
        near(Indicators.ema(C, 200), 1334.2182402068445);
    }

    @Test
    @DisplayName("MACD 12/26/9")
    void macd() {
        double[] m = Indicators.macd(C, 12, 26, 9);
        near(m[0], -26.224463246621553);
        near(m[1], -20.270081752994184);
        near(m[2], -5.95438149362737);
    }

    @Test
    @DisplayName("Bollinger 20, 2σ")
    void bollinger() {
        double[] b = Indicators.bollinger(C, 20, 2.0);
        near(b[0], 1325.3813210543124);
        near(b[2], 1173.758678945688);
    }

    @Test
    @DisplayName("Wilder ATR(14) is computed through the latest bar, not from the oldest 14")
    void atr() {
        near(Indicators.atr(BARS, 14), 19.611310729377074);
        // The defect this replaces averaged the oldest 14 true ranges. With calm early bars and
        // volatile recent ones, the correct ATR follows the recent range.
        List<PriceHistory> regime = new java.util.ArrayList<>();
        java.time.LocalDate d = java.time.LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 60; i++) {
            double range = i < 30 ? 1 : 10;
            regime.add(Bars.bar(d.plusDays(i), 100, 100 + range / 2, 100 - range / 2, 100, 1));
        }
        // Wilder smoothing after 30 wide bars: 10 − 9·(13/14)^30 ≈ 9.03; the oldest-14 average was 1.
        assertThat(Indicators.atr(regime, 14)).isCloseTo(10 - 9 * Math.pow(13.0 / 14, 30), within(1e-9));
    }

    @Test
    @DisplayName("Wilder ADX(14) with +DI/−DI")
    void adx() {
        double[] a = Indicators.adx(BARS, 14);
        near(a[0], 38.731945498266136);
        near(a[1], 7.062622657980867);
        near(a[2], 35.000792375214324);
    }

    @Test
    @DisplayName("Daily volatility = sample σ of the last 120 log returns")
    void volatility() {
        near(Indicators.stdev(Indicators.logReturns(C, 120)), 0.012434266914415393);
    }

    @Test
    @DisplayName("Every indicator returns null, not 0 or 50, below its data requirement")
    void insufficient() {
        List<Double> ten = C.subList(0, 10);
        assertThat(Indicators.rsi(ten, 14)).isNull();
        assertThat(Indicators.sma(ten, 20)).isNull();
        assertThat(Indicators.ema(ten, 20)).isNull();
        assertThat(Indicators.macd(C.subList(0, 33), 12, 26, 9)).isNull();
        assertThat(Indicators.macd(C.subList(0, 34), 12, 26, 9)).isNotNull();
        assertThat(Indicators.bollinger(ten, 20, 2)).isNull();
        assertThat(Indicators.atr(BARS.subList(0, 14), 14)).isNull();
        assertThat(Indicators.adx(BARS.subList(0, 28), 14)).isNull();
    }

    @Test
    @DisplayName("RSI of a flat series is 50, and of a rising one 100")
    void rsiEdges() {
        assertThat(Indicators.rsi(java.util.Collections.nCopies(30, 100.0), 14)).isEqualTo(50.0);
        List<Double> up = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) up.add(100.0 + i);
        assertThat(Indicators.rsi(up, 14)).isEqualTo(100.0);
    }
}
