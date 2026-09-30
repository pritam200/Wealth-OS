package com.marketai.forecast.service;

import com.marketai.forecast.dto.ForecastResponse;
import com.marketai.forecast.dto.ForecastResponse.*;
import com.marketai.forecast.model.ForecastBacktester;
import com.marketai.forecast.model.VolatilityModel;
import com.marketai.market.entity.PriceHistory;
import com.marketai.market.quality.DailySeries;
import com.marketai.market.quality.DataIssue;
import com.marketai.market.quality.SeriesStatus;
import com.marketai.market.service.MarketDataService;
import com.marketai.technical.dto.PriceLevel;
import com.marketai.technical.dto.TechnicalAnalysisDto;
import com.marketai.technical.service.TechnicalIndicatorService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;

/**
 * Model-estimated price ranges.
 *
 * Model: zero-drift log-normal. ln(P[t+h]/P[t]) ~ N(0, (σ·√h)²) with σ the realised daily
 * volatility (see {@link VolatilityModel}). The model makes no directional call — a 10-year,
 * 15-instrument backtest found trend labels, RSI and moving-average position carried no
 * measurable edge on 5–60 session returns, so the fixed trend→probability table this replaced
 * (e.g. "strong uptrend = 45% bull") was removed rather than re-tuned.
 *
 * Scenarios are bands of that distribution: Bear 5th–25th percentile, Base 25th–75th, Bull
 * 75th–95th. Their probabilities are not asserted: each is the measured share of this
 * instrument's own historical outcomes that fell in the equivalent band, from a walk-forward
 * backtest ({@link ForecastBacktester}) with no look-ahead — shown only when there are at least
 * 30 independent windows, otherwise "unavailable".
 */
@Service
@RequiredArgsConstructor
public class ForecastService {

    private final MarketDataService marketDataService;
    private final TechnicalIndicatorService technical;

    /** Horizons in trading sessions; the old 1W/2W/4W/3M codes are accepted as aliases. */
    private static final Map<String, Integer> HORIZONS = new LinkedHashMap<>();
    static {
        HORIZONS.put("1D", 1); HORIZONS.put("5D", 5); HORIZONS.put("10D", 10); HORIZONS.put("20D", 20); HORIZONS.put("60D", 60);
        HORIZONS.put("1W", 5); HORIZONS.put("2W", 10); HORIZONS.put("4W", 20); HORIZONS.put("1M", 20); HORIZONS.put("3M", 60);
    }

    public static int tradingDays(String horizon) {
        Integer d = horizon == null ? null : HORIZONS.get(horizon.trim().toUpperCase());
        if (d == null) throw new IllegalArgumentException("Unknown horizon '" + horizon + "'. Use 1D, 5D, 10D, 20D or 60D.");
        return d;
    }

    public static final String METHODOLOGY = "Zero-drift log-normal range: ln(P[t+h]/P[t]) ~ N(0, (σ√h)²), σ = sample standard deviation "
            + "of the last 120 daily log returns. Bands are percentiles of that distribution (Bear 5–25, Base 25–75, Bull 75–95). "
            + "Scenario probabilities are the measured share of this instrument's past outcomes in each band from a walk-forward backtest "
            + "(model rebuilt at every past session from data up to that session only); shown only with ≥30 independent windows. "
            + "The model has no directional view.";

    public ForecastResponse forecast(String symbol, String horizon, String displayName) {
        int h = tradingDays(horizon);
        String code = h + "D";
        DailySeries series = marketDataService.getDailySeries(symbol, VolatilityModel.WINDOW + 1);
        String name = displayName != null ? displayName : series.symbol();
        ForecastResponse.ForecastResponseBuilder out = ForecastResponse.builder()
                .symbol(series.symbol()).displayName(name).horizon(code).tradingDays(h)
                .dataPoints(series.size()).dataIssues(series.issues())
                .firstBarDate(series.isEmpty() ? null : series.bars().get(0).getDate())
                .priceDate(series.lastBarDate())
                .currentPrice(series.last() != null ? series.last().getClose().doubleValue() : null)
                .source(series.provider()).methodology(METHODOLOGY)
                .scenarios(List.of());

        if (series.status() == SeriesStatus.INSUFFICIENT_DATA) {
            return out.status(SeriesStatus.INSUFFICIENT_DATA.name()).statusReason(String.format(
                    "Only %d valid daily bar(s) for %s; the volatility model needs %d. No range is shown.",
                    series.size(), series.symbol(), VolatilityModel.WINDOW + 1)).build();
        }
        if (series.status() == SeriesStatus.STALE_DATA) {
            return out.status(SeriesStatus.STALE_DATA.name()).statusReason(String.format(
                    "Newest bar is from %s, %d session(s) behind %s, and could not be refreshed. A range from stale data is not shown.",
                    series.lastBarDate(), series.sessionsBehind(), series.expectedSession())).build();
        }
        if (series.hasWarningWithin(VolatilityModel.WINDOW + h, "POSSIBLE_UNADJUSTED_SPLIT")) {
            return out.status(SeriesStatus.DATA_QUALITY_WARNING.name()).statusReason(
                    "A price discontinuity that looks like an unadjusted split or bonus falls inside the volatility window, "
                    + "so measured volatility would be wrong. No range is shown until the history is corrected.").build();
        }

        List<Double> closes = series.closes();
        double price = closes.get(closes.size() - 1);
        double daily = VolatilityModel.dailySigma(closes);
        double hs = VolatilityModel.horizonSigma(daily, h);
        TechnicalAnalysisDto ta = technical.analyse(series);
        ForecastBacktester.Result bt = ForecastBacktester.run(series.symbol(),
                series.bars().stream().map(PriceHistory::getDate).toList(), closes, h);
        boolean measured = bt != null && bt.getEffectiveSample() >= ForecastBacktester.MIN_EFFECTIVE_SAMPLE;

        double[] q = ForecastBacktester.BAND_QUANTILES;
        List<Scenario> scenarios = List.of(
                scenario("Bear", "down", q[0], q[1], 1, price, hs, bt, measured),
                scenario("Base", "flat", q[1], q[2], 2, price, hs, bt, measured),
                scenario("Bull", "up", q[2], q[3], 3, price, hs, bt, measured));

        Range r50 = Range.builder().low(r2(VolatilityModel.quantilePrice(price, hs, 0.25))).high(r2(VolatilityModel.quantilePrice(price, hs, 0.75)))
                .nominalCoverage(0.5).historicalCoverage(bt != null ? r4(bt.getRange50Coverage()) : null).build();
        Range r90 = Range.builder().low(r2(VolatilityModel.quantilePrice(price, hs, 0.05))).high(r2(VolatilityModel.quantilePrice(price, hs, 0.95)))
                .nominalCoverage(0.9).historicalCoverage(bt != null ? r4(bt.getRange90Coverage()) : null).build();

        boolean warn = series.status() == SeriesStatus.DATA_QUALITY_WARNING;
        String effNote = bt == null ? "no backtest possible" : bt.getEffectiveSample() + " independent " + code + " windows";
        return out
                .status(series.status().name())
                .statusReason(warn ? "Range computed; see data issues." : null)
                .horizonEndsAround(addSessions(series.lastBarDate(), h))
                .volatility(Volatility.builder().dailyPct(r4(daily * 100)).annualizedPct(r2(VolatilityModel.annualised(daily) * 100))
                        .horizonPct(r4(hs * 100)).window(VolatilityModel.WINDOW).method(VolatilityModel.METHOD).build())
                .range50(r50).range90(r90).scenarios(scenarios)
                .probabilityStatus(measured ? "EMPIRICAL" : "UNAVAILABLE")
                .probabilityNote(measured
                        ? "Measured over " + effNote + " (" + bt.getFirstForecastDate() + " to " + bt.getLastForecastDate() + "), 95% interval shown. Past frequencies, not a promise."
                        : "Probability unavailable — only " + effNote + " in stored history; at least " + ForecastBacktester.MIN_EFFECTIVE_SAMPLE + " are needed.")
                .calibration(calibration(bt))
                .directional(directional(bt))
                .pointErrors(pointErrors(bt, code))
                .trend(ta.getTrend())
                .rsi(d(ta.getRsi())).sma50(d(ta.getSma50())).sma200(d(ta.getSma200()))
                .support(d(ta.getSupport())).resistance(d(ta.getResistance()))
                .keyDrivers(drivers(ta, daily))
                .keyRisks(risks(series, bt, h))
                .basis(String.format("From the %s close of ₹%.2f: daily σ %.2f%% (annualised %.1f%%, %d returns) → %s σ %.2f%%. %s",
                        series.lastBarDate(), price, daily * 100, VolatilityModel.annualised(daily) * 100, VolatilityModel.WINDOW,
                        code, hs * 100, measured ? "Band probabilities measured from this instrument's history." : "Band probabilities unavailable."))
                .backtest(bt)
                .build();
    }

    /**
     * Walk-forward backtest of the range model for each symbol and horizon, plus a pooled
     * summary weighted by observations. Uses exactly the series and model the live forecast uses.
     */
    public Map<String, Object> backtest(List<String> symbols, List<String> horizons) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> perSymbol = new LinkedHashMap<>();
        Map<String, List<ForecastBacktester.Result>> byHorizon = new LinkedHashMap<>();
        for (String sym : symbols) {
            DailySeries series = marketDataService.getDailySeries(sym, VolatilityModel.WINDOW + 1);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("status", series.status());
            row.put("bars", series.size());
            row.put("from", series.isEmpty() ? null : series.bars().get(0).getDate());
            row.put("to", series.lastBarDate());
            List<LocalDate> dates = series.bars().stream().map(PriceHistory::getDate).toList();
            for (String hz : horizons) {
                int h = tradingDays(hz);
                ForecastBacktester.Result r = series.size() > VolatilityModel.WINDOW ? ForecastBacktester.run(series.symbol(), dates, series.closes(), h) : null;
                row.put(h + "D", r);
                if (r != null) byHorizon.computeIfAbsent(h + "D", k -> new ArrayList<>()).add(r);
            }
            perSymbol.put(series.symbol(), row);
        }
        Map<String, Object> pooled = new LinkedHashMap<>();
        byHorizon.forEach((k, list) -> pooled.put(k, pool(list)));
        out.put("methodology", METHODOLOGY);
        out.put("pooled", pooled);
        out.put("perSymbol", perSymbol);
        return out;
    }

    static Map<String, Object> pool(List<ForecastBacktester.Result> rs) {
        double n = rs.stream().mapToDouble(ForecastBacktester.Result::getObservations).sum();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instruments", rs.size());
        m.put("observations", (long) n);
        m.put("effectiveSample", rs.stream().mapToInt(ForecastBacktester.Result::getEffectiveSample).sum());
        double[] reg = new double[5];
        for (ForecastBacktester.Result r : rs) for (int i = 0; i < 5; i++) reg[i] += r.getRegionFrequency()[i] * r.getObservations() / n;
        Map<String, Double> regions = new LinkedHashMap<>();
        String[] names = {"below5", "bear5to25", "base25to75", "bull75to95", "above95"};
        for (int i = 0; i < 5; i++) regions.put(names[i], r4(reg[i]));
        m.put("regionFrequency", regions);
        m.put("regionNominal", Map.of("below5", 0.05, "bear5to25", 0.20, "base25to75", 0.50, "bull75to95", 0.20, "above95", 0.05));
        m.put("range50Coverage", r4(wmean(rs, ForecastBacktester.Result::getRange50Coverage, n)));
        m.put("range90Coverage", r4(wmean(rs, ForecastBacktester.Result::getRange90Coverage, n)));
        List<Map<String, Double>> curve = new ArrayList<>();
        for (int i = 0; i < ForecastBacktester.CURVE.length; i++) {
            final int k = i;
            curve.add(Map.of("nominal", ForecastBacktester.CURVE[i],
                    "empirical", r4(wmean(rs, r -> r.getCalibration().get(k).getEmpirical(), n))));
        }
        m.put("calibration", curve);
        m.put("upFrequency", r4(wmean(rs, ForecastBacktester.Result::getUpFrequency, n)));
        m.put("maBaselineHitRate", r4(wmean(rs, ForecastBacktester.Result::getMaBaselineHitRate, n)));
        m.put("brierModel", r4(wmean(rs, ForecastBacktester.Result::getBrierModel, n)));
        m.put("brierTrailingFrequency", r4(wmean(rs, r -> r.getBrierTrailingFrequency() != null ? r.getBrierTrailingFrequency() : 0.25, n)));
        m.put("maeRandomWalkPct", r4(wmean(rs, ForecastBacktester.Result::getMaeRandomWalkPct, n)));
        m.put("rmseRandomWalkPct", r4(Math.sqrt(wmean(rs, r -> r.getRmseRandomWalkPct() * r.getRmseRandomWalkPct(), n))));
        m.put("maeDriftPct", r4(wmean(rs, r -> r.getMaeDriftPct() != null ? r.getMaeDriftPct() : r.getMaeRandomWalkPct(), n)));
        m.put("rmseDriftPct", r4(Math.sqrt(wmean(rs, r -> r.getRmseDriftPct() != null ? r.getRmseDriftPct() * r.getRmseDriftPct() : r.getRmseRandomWalkPct() * r.getRmseRandomWalkPct(), n))));
        m.put("maeMaReversionPct", r4(wmean(rs, r -> r.getMaeMaReversionPct() != null ? r.getMaeMaReversionPct() : r.getMaeRandomWalkPct(), n)));
        m.put("rmseMaReversionPct", r4(Math.sqrt(wmean(rs, r -> r.getRmseMaReversionPct() != null ? r.getRmseMaReversionPct() * r.getRmseMaReversionPct() : r.getRmseRandomWalkPct() * r.getRmseRandomWalkPct(), n))));
        m.put("regimes", poolSegments(rs, ForecastBacktester.Result::getRegimes));
        m.put("periods", poolSegments(rs, ForecastBacktester.Result::getPeriods));
        return m;
    }

    private static double wmean(List<ForecastBacktester.Result> rs, java.util.function.ToDoubleFunction<ForecastBacktester.Result> f, double n) {
        double s = 0;
        for (ForecastBacktester.Result r : rs) s += f.applyAsDouble(r) * r.getObservations();
        return s / n;
    }

    private static Map<String, Map<String, Double>> poolSegments(List<ForecastBacktester.Result> rs,
            java.util.function.Function<ForecastBacktester.Result, Map<String, ForecastBacktester.Segment>> get) {
        Map<String, double[]> acc = new LinkedHashMap<>();
        for (ForecastBacktester.Result r : rs) {
            get.apply(r).forEach((k, sg) -> {
                double[] a = acc.computeIfAbsent(k, x -> new double[5]);
                a[0] += sg.getObservations();
                a[1] += sg.getRange50Coverage() * sg.getObservations();
                a[2] += sg.getRange90Coverage() * sg.getObservations();
                a[3] += sg.getUpFrequency() * sg.getObservations();
                a[4] += sg.getMeanReturnPct() * sg.getObservations();
            });
        }
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        acc.forEach((k, a) -> out.put(k, Map.of("observations", a[0], "range50Coverage", r4(a[1] / a[0]),
                "range90Coverage", r4(a[2] / a[0]), "upFrequency", r4(a[3] / a[0]), "meanReturnPct", r4(a[4] / a[0]))));
        return out;
    }

    private static Scenario scenario(String label, String dir, double qLo, double qHi, int region, double price, double hs,
                                     ForecastBacktester.Result bt, boolean measured) {
        double lo = VolatilityModel.quantilePrice(price, hs, qLo), hi = VolatilityModel.quantilePrice(price, hs, qHi);
        double ref = VolatilityModel.quantilePrice(price, hs, (qLo + qHi) / 2);
        return Scenario.builder().label(label).direction(dir).quantileLow(qLo).quantileHigh(qHi)
                .low(r2(lo)).high(r2(hi)).reference(r2(ref))
                .movePctLow(r2((lo / price - 1) * 100)).movePctHigh(r2((hi / price - 1) * 100))
                .nominalProbability(r2((qHi - qLo) * 100))
                .probability(measured ? r2(bt.getRegionFrequency()[region] * 100) : null)
                .probabilityCiLow(measured ? r2(bt.getRegionCiLow()[region] * 100) : null)
                .probabilityCiHigh(measured ? r2(bt.getRegionCiHigh()[region] * 100) : null)
                .build();
    }

    static Calibration calibration(ForecastBacktester.Result bt) {
        if (bt == null) return Calibration.builder().label("UNMEASURED").summary("Not enough history to backtest the model.").build();
        double gap = Math.max(Math.abs(bt.getRange50Coverage() - 0.5), Math.abs(bt.getRange90Coverage() - 0.9)) * 100;
        String label = bt.getEffectiveSample() < ForecastBacktester.MIN_EFFECTIVE_SAMPLE ? "UNMEASURED"
                : gap <= 5 ? "WELL_CALIBRATED" : gap <= 10 ? "APPROXIMATELY_CALIBRATED" : "POORLY_CALIBRATED";
        double tail = bt.getRegionFrequency()[0] + bt.getRegionFrequency()[4];
        return Calibration.builder().label(label)
                .summary(String.format("The 50%% range held %.0f%% and the 90%% range %.0f%% of %d past windows (%d independent).",
                        bt.getRange50Coverage() * 100, bt.getRange90Coverage() * 100, bt.getObservations(), bt.getEffectiveSample()))
                .observations(bt.getObservations()).effectiveSample(bt.getEffectiveSample())
                .from(bt.getFirstForecastDate()).to(bt.getLastForecastDate())
                .curve(bt.getCalibration()).tailFrequency(r4(tail)).build();
    }

    static Directional directional(ForecastBacktester.Result bt) {
        if (bt == null) return Directional.builder().modelUpProbability(0.5).summary("Unmeasured.").build();
        return Directional.builder().modelUpProbability(0.5)
                .historicalUpFrequency(r4(bt.getUpFrequency()))
                .movingAverageBaselineHitRate(r4(bt.getMaBaselineHitRate()))
                .brierModel(r4(bt.getBrierModel()))
                .brierTrailingFrequency(bt.getBrierTrailingFrequency() != null ? r4(bt.getBrierTrailingFrequency()) : null)
                .summary(String.format("No directional call is made. Historically %.0f%% of windows ended higher; calling direction from price vs SMA50 was right %.0f%% of the time%s.",
                        bt.getUpFrequency() * 100, bt.getMaBaselineHitRate() * 100,
                        bt.getMaBaselineHitRate() > bt.getUpFrequency() ? "" : " — no better than always saying \"up\""))
                .build();
    }

    static PointErrors pointErrors(ForecastBacktester.Result bt, String code) {
        if (bt == null) return null;
        return PointErrors.builder()
                .maeRandomWalkPct(r4(bt.getMaeRandomWalkPct())).rmseRandomWalkPct(r4(bt.getRmseRandomWalkPct()))
                .maeDriftPct(bt.getMaeDriftPct() != null ? r4(bt.getMaeDriftPct()) : null)
                .rmseDriftPct(bt.getRmseDriftPct() != null ? r4(bt.getRmseDriftPct()) : null)
                .maeMaReversionPct(bt.getMaeMaReversionPct() != null ? r4(bt.getMaeMaReversionPct()) : null)
                .rmseMaReversionPct(bt.getRmseMaReversionPct() != null ? r4(bt.getRmseMaReversionPct()) : null)
                .summary(String.format("Typical %s move error: %.2f%% using the last close (the model's median), %s using trailing drift, %s using reversion to SMA20.",
                        code, bt.getMaeRandomWalkPct(),
                        bt.getMaeDriftPct() != null ? String.format("%.2f%%", bt.getMaeDriftPct()) : "n/a",
                        bt.getMaeMaReversionPct() != null ? String.format("%.2f%%", bt.getMaeMaReversionPct()) : "n/a"))
                .build();
    }

    private static List<String> drivers(TechnicalAnalysisDto ta, double daily) {
        List<String> d = new ArrayList<>();
        d.add(String.format("Volatility %.2f%%/day (annualised %.1f%%) sets the width of every range.", daily * 100, VolatilityModel.annualised(daily) * 100));
        if (ta.getTrendAssessment() != null) {
            d.add(String.format("Trend: %s (%d bullish / %d bearish of %d votes) — context only, not used for the range.",
                    ta.getTrend(), ta.getTrendAssessment().getBullishVotes(), ta.getTrendAssessment().getBearishVotes(), ta.getTrendAssessment().getVotesAvailable()));
        }
        if (ta.getRsi() != null) d.add("Momentum: RSI(14) " + ta.getRsi() + ".");
        PriceLevel s = ta.getLevels() != null ? ta.getLevels().getNearestSupport() : null;
        PriceLevel r = ta.getLevels() != null ? ta.getLevels().getNearestResistance() : null;
        d.add("Support: " + (s != null ? "₹" + s.getPrice() + " (" + s.getSource() + ", " + s.getDistancePct() + "%)" : "no reliable level") + "; resistance: "
                + (r != null ? "₹" + r.getPrice() + " (" + r.getSource() + ", +" + r.getDistancePct() + "%)" : "no reliable level") + ".");
        if (ta.getVolume() != null && ta.getVolume().getRelativeVolume() != null) {
            d.add("Volume: " + ta.getVolume().getRelativeVolume() + "× the 20-session average.");
        }
        return d;
    }

    private static List<String> risks(DailySeries s, ForecastBacktester.Result bt, int h) {
        List<String> r = new ArrayList<>();
        r.add("The range assumes volatility stays near its recent level; results, policy events or market shocks can widen it abruptly.");
        if (bt != null) {
            double tail = (bt.getRegionFrequency()[0] + bt.getRegionFrequency()[4]) * 100;
            r.add(String.format("Historically %.1f%% of %dD outcomes ended outside the 90%% range (nominal 10%%).", tail, h));
        }
        for (DataIssue i : s.issues()) if (i.isWarning()) r.add("Data: " + i.detail());
        return r.size() > 8 ? r.subList(0, 8) : r;
    }

    private static LocalDate addSessions(LocalDate d, int n) {
        LocalDate x = d;
        for (int i = 0; i < n; ) {
            x = x.plusDays(1);
            if (x.getDayOfWeek() != DayOfWeek.SATURDAY && x.getDayOfWeek() != DayOfWeek.SUNDAY) i++;
        }
        return x;
    }

    private static Double d(BigDecimal v) { return v == null ? null : v.doubleValue(); }
    private static double r2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double r4(double v) { return Math.round(v * 10000.0) / 10000.0; }
}
