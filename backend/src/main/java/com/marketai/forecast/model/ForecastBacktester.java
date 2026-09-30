package com.marketai.forecast.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.*;

/**
 * Walk-forward backtest of the forecast model on one instrument's own history.
 *
 * At every session t the model is rebuilt from closes up to and including t only (σ from the
 * 120 returns ending at t, baselines from data ending at t) and compared with the close h
 * sessions later. Nothing after t is used at t. Outcomes are standardised as
 * z = ln(C[t+h]/C[t]) / (σ_t·√h), so one sample covers every price level.
 *
 * Consecutive windows overlap by h−1 sessions and are not independent, so every sample size
 * used for a confidence interval or a sufficiency check is the effective sample N/h.
 */
public final class ForecastBacktester {

    private ForecastBacktester() {}

    /** Band edges as quantiles of the model distribution: bear 5–25%, base 25–75%, bull 75–95%. */
    public static final double[] BAND_QUANTILES = {0.05, 0.25, 0.75, 0.95};
    /** Nominal probability of each of the five regions: below 5%, bear, base, bull, above 95%. */
    public static final double[] NOMINAL = {0.05, 0.20, 0.50, 0.20, 0.05};
    /** Central-interval coverages the calibration curve is measured at. */
    public static final double[] CURVE = {0.10, 0.20, 0.30, 0.40, 0.50, 0.60, 0.70, 0.80, 0.90, 0.95};
    /** Minimum effective (non-overlapping) sample for an empirical probability to be shown. */
    public static final int MIN_EFFECTIVE_SAMPLE = 30;
    static final int BASE_RATE_WINDOW = 250;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Result {
        private String symbol;
        private int horizon;
        private int observations;
        private int effectiveSample;
        private LocalDate firstForecastDate;
        private LocalDate lastForecastDate;
        /** Frequency of outcomes in each region: below 5%, bear, base, bull, above 95%. */
        private double[] regionFrequency;
        private double[] regionCiLow;
        private double[] regionCiHigh;
        private List<CurvePoint> calibration;
        private double range50Coverage;
        private double range90Coverage;
        /** Directional: share of windows that ended higher (the buy-and-hold call's hit rate). */
        private double upFrequency;
        /** Moving-average baseline: up if close > SMA50 at t, else down. */
        private double maBaselineHitRate;
        private int maBaselineCalls;
        /** Brier score of the model's P(up) = 0.5 and of the trailing 250-session up-frequency. */
        private double brierModel;
        private Double brierTrailingFrequency;
        /** Point errors of the h-session log return, percent. Model median = previous close (random walk). */
        private double maeRandomWalkPct;
        private double rmseRandomWalkPct;
        private Double maeDriftPct;
        private Double rmseDriftPct;
        private Double maeMaReversionPct;
        private Double rmseMaReversionPct;
        private Map<String, Segment> regimes;
        private Map<String, Segment> periods;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CurvePoint {
        private double nominal;
        private double empirical;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Segment {
        private int observations;
        private double range50Coverage;
        private double range90Coverage;
        private double upFrequency;
        private double meanReturnPct;
    }

    /** Named market episodes the results are broken out by (dates of the forecast origin). */
    public static final Map<String, LocalDate[]> PERIODS = new LinkedHashMap<>();
    static {
        PERIODS.put("COVID crash (20 Feb – 31 Mar 2020)", new LocalDate[]{LocalDate.of(2020, 2, 20), LocalDate.of(2020, 3, 31)});
        PERIODS.put("Recovery (Apr 2020 – Mar 2021)", new LocalDate[]{LocalDate.of(2020, 4, 1), LocalDate.of(2021, 3, 31)});
        PERIODS.put("2022 drawdown (Jan – Jun 2022)", new LocalDate[]{LocalDate.of(2022, 1, 1), LocalDate.of(2022, 6, 30)});
        PERIODS.put("Sep 2024 – Mar 2025 correction", new LocalDate[]{LocalDate.of(2024, 9, 27), LocalDate.of(2025, 3, 31)});
    }

    public static Result run(String symbol, List<LocalDate> dates, List<Double> closes, int h) {
        int n = closes.size(), w = VolatilityModel.WINDOW;
        if (n < w + h + 2) return null;
        double[] z4 = new double[BAND_QUANTILES.length];
        for (int i = 0; i < z4.length; i++) z4[i] = Stats.normInv(BAND_QUANTILES[i]);
        double[] zc = new double[CURVE.length];
        for (int i = 0; i < CURVE.length; i++) zc[i] = Stats.normInv(0.5 + CURVE[i] / 2);
        double z50 = Stats.normInv(0.75), z90 = Stats.normInv(0.95);

        double[] lr = new double[n], s1 = new double[n + 1], sc = new double[n + 1];
        for (int i = 1; i < n; i++) lr[i] = Math.log(closes.get(i) / closes.get(i - 1));
        for (int i = 0; i < n; i++) {
            s1[i + 1] = s1[i] + lr[i];
            sc[i + 1] = sc[i] + closes.get(i);
        }
        // up[k] = 1 when the window starting at k ended higher; known from session k+h on.
        double[] upPrefix = new double[n + 1];
        for (int k = 0; k < n; k++) upPrefix[k + 1] = upPrefix[k] + (k + h < n && closes.get(k + h) > closes.get(k) ? 1 : 0);

        double[] sigma = dailySigmas(closes);
        int[] region = new int[5];
        int[] curveHits = new int[CURVE.length];
        int obs = 0, in50 = 0, in90 = 0, ups = 0, maCalls = 0, maHits = 0, trailN = 0;
        double brierM = 0, brierT = 0, aeRw = 0, seRw = 0, aeDr = 0, seDr = 0, aeMa = 0, seMa = 0;
        int drN = 0, maN = 0;
        Map<String, double[]> reg = new LinkedHashMap<>(), per = new LinkedHashMap<>();
        LocalDate first = null, last = null;

        for (int t = w; t + h < n; t++) {
            double daily = sigma[t];
            if (!(daily > 0)) continue;
            double var = daily * daily;
            double sig = daily * Math.sqrt(h);
            double ret = Math.log(closes.get(t + h) / closes.get(t));
            double z = ret / sig;
            obs++;
            if (first == null) first = dates.get(t);
            last = dates.get(t);

            int r = z < z4[0] ? 0 : z < z4[1] ? 1 : z <= z4[2] ? 2 : z <= z4[3] ? 3 : 4;
            region[r]++;
            for (int i = 0; i < CURVE.length; i++) if (Math.abs(z) <= zc[i]) curveHits[i]++;
            boolean c50 = Math.abs(z) <= z50, c90 = Math.abs(z) <= z90, up = ret > 0;
            if (c50) in50++;
            if (c90) in90++;
            if (up) ups++;
            brierM += Math.pow(0.5 - (up ? 1 : 0), 2);

            aeRw += Math.abs(ret) * 100;
            seRw += ret * ret * 1e4;

            if (t >= 50) {
                double sma50 = (sc[t + 1] - sc[t - 49]) / 50;
                boolean callUp = closes.get(t) > sma50;
                maCalls++;
                if (callUp == up) maHits++;
            }
            if (t >= 20) {
                double sma20 = (sc[t + 1] - sc[t - 19]) / 20;
                double e = ret - Math.log(sma20 / closes.get(t));
                aeMa += Math.abs(e) * 100; seMa += e * e * 1e4; maN++;
            }
            if (t >= BASE_RATE_WINDOW) {
                double drift = (s1[t + 1] - s1[t - BASE_RATE_WINDOW + 1]) / BASE_RATE_WINDOW * h;
                double e = ret - drift;
                aeDr += Math.abs(e) * 100; seDr += e * e * 1e4; drN++;
                int k0 = t - BASE_RATE_WINDOW, k1 = t - h; // windows fully known at t
                if (k1 > k0) {
                    double p = (upPrefix[k1 + 1] - upPrefix[k0]) / (k1 - k0 + 1);
                    brierT += Math.pow(p - (up ? 1 : 0), 2);
                    trailN++;
                }
                double trailing = closes.get(t) / closes.get(t - BASE_RATE_WINDOW) - 1;
                String rg = trailing > 0.15 ? "Bull (trailing 1y > +15%)" : trailing < -0.15 ? "Bear (trailing 1y < −15%)" : "Sideways (trailing 1y within ±15%)";
                acc(reg, rg, c50, c90, up, ret);
            }
            double annual = Math.sqrt(var) * Math.sqrt(252);
            acc(reg, annual > 0.35 ? "High volatility (σ > 35%/yr)" : "Normal volatility (σ ≤ 35%/yr)", c50, c90, up, ret);
            for (Map.Entry<String, LocalDate[]> p : PERIODS.entrySet()) {
                LocalDate d = dates.get(t);
                if (!d.isBefore(p.getValue()[0]) && !d.isAfter(p.getValue()[1])) acc(per, p.getKey(), c50, c90, up, ret);
            }
        }
        if (obs == 0) return null;

        int eff = Math.max(1, obs / h);
        double[] freq = new double[5], lo = new double[5], hi = new double[5];
        for (int i = 0; i < 5; i++) {
            freq[i] = (double) region[i] / obs;
            double[] ci = Stats.wilson(freq[i], eff, 1.96);
            lo[i] = ci[0]; hi[i] = ci[1];
        }
        List<CurvePoint> curve = new ArrayList<>();
        for (int i = 0; i < CURVE.length; i++) curve.add(new CurvePoint(CURVE[i], (double) curveHits[i] / obs));

        return Result.builder()
                .symbol(symbol).horizon(h).observations(obs).effectiveSample(eff)
                .firstForecastDate(first).lastForecastDate(last)
                .regionFrequency(freq).regionCiLow(lo).regionCiHigh(hi)
                .calibration(curve)
                .range50Coverage((double) in50 / obs).range90Coverage((double) in90 / obs)
                .upFrequency((double) ups / obs)
                .maBaselineHitRate(maCalls > 0 ? (double) maHits / maCalls : 0).maBaselineCalls(maCalls)
                .brierModel(brierM / obs)
                .brierTrailingFrequency(trailN > 0 ? brierT / trailN : null)
                .maeRandomWalkPct(aeRw / obs).rmseRandomWalkPct(Math.sqrt(seRw / obs))
                .maeDriftPct(drN > 0 ? aeDr / drN : null).rmseDriftPct(drN > 0 ? Math.sqrt(seDr / drN) : null)
                .maeMaReversionPct(maN > 0 ? aeMa / maN : null).rmseMaReversionPct(maN > 0 ? Math.sqrt(seMa / maN) : null)
                .regimes(segments(reg)).periods(segments(per))
                .build();
    }

    /**
     * Daily σ as the model would have measured it at each session t: sample σ of the
     * {@value VolatilityModel#WINDOW} log returns ending at t (NaN before enough history).
     * Depends only on closes[0..t] — the property the look-ahead test pins down.
     */
    public static double[] dailySigmas(List<Double> closes) {
        int n = closes.size(), w = VolatilityModel.WINDOW;
        double[] out = new double[n];
        java.util.Arrays.fill(out, Double.NaN);
        double s1 = 0, s2 = 0;
        double[] lr = new double[n];
        for (int i = 1; i < n; i++) {
            lr[i] = Math.log(closes.get(i) / closes.get(i - 1));
            s1 += lr[i]; s2 += lr[i] * lr[i];
            if (i > w) { s1 -= lr[i - w]; s2 -= lr[i - w] * lr[i - w]; }
            if (i >= w) {
                double var = (s2 - s1 * s1 / w) / (w - 1);
                out[i] = var > 0 ? Math.sqrt(var) : Double.NaN;
            }
        }
        return out;
    }

    private static void acc(Map<String, double[]> m, String key, boolean c50, boolean c90, boolean up, double ret) {
        double[] a = m.computeIfAbsent(key, k -> new double[5]);
        a[0]++; if (c50) a[1]++; if (c90) a[2]++; if (up) a[3]++; a[4] += ret;
    }

    private static Map<String, Segment> segments(Map<String, double[]> m) {
        Map<String, Segment> out = new LinkedHashMap<>();
        m.forEach((k, a) -> out.put(k, new Segment((int) a[0], a[1] / a[0], a[2] / a[0], a[3] / a[0], (Math.exp(a[4] / a[0]) - 1) * 100)));
        return out;
    }
}
