package com.marketai.technical.service;

import com.marketai.market.entity.PriceHistory;

import java.util.ArrayList;
import java.util.List;

/**
 * Standard indicator definitions, as pure functions over oldest-first data. Each returns null
 * (never 0 or a default) when there are too few bars for its definition. Every formula here is
 * checked against an independent implementation in {@code IndicatorReferenceTest}.
 */
public final class Indicators {

    private Indicators() {}

    /** Simple moving average of the last {@code period} values. Needs {@code period} values. */
    public static Double sma(List<Double> v, int period) {
        if (period <= 0 || v.size() < period) return null;
        double s = 0;
        for (int i = v.size() - period; i < v.size(); i++) s += v.get(i);
        return s / period;
    }

    /** SMA of the window ending {@code barsAgo} bars before the last value. */
    public static Double smaAt(List<Double> v, int period, int barsAgo) {
        if (barsAgo < 0 || v.size() - barsAgo < period) return null;
        return sma(v.subList(0, v.size() - barsAgo), period);
    }

    /**
     * Exponential moving average series, k = 2/(n+1), seeded with the SMA of the first n values
     * (the TA-Lib convention). Element 0 corresponds to input index n−1.
     */
    public static List<Double> emaSeries(List<Double> v, int period) {
        List<Double> out = new ArrayList<>();
        if (period <= 0 || v.size() < period) return out;
        double k = 2.0 / (period + 1);
        double ema = 0;
        for (int i = 0; i < period; i++) ema += v.get(i) / period;
        out.add(ema);
        for (int i = period; i < v.size(); i++) {
            ema = v.get(i) * k + ema * (1 - k);
            out.add(ema);
        }
        return out;
    }

    public static Double ema(List<Double> v, int period) {
        List<Double> e = emaSeries(v, period);
        return e.isEmpty() ? null : e.get(e.size() - 1);
    }

    /** Bars needed before an EMA is trustworthy: the seed plus enough bars for the seed's weight to decay. */
    public static int emaBarsRequired(int period) {
        return period * 2;
    }

    /**
     * Wilder RSI: average gain/loss seeded with the simple mean of the first n changes, then
     * smoothed avg = (avg·(n−1) + x)/n through the last bar. Needs n+1 closes.
     */
    public static Double rsi(List<Double> closes, int period) {
        if (period <= 0 || closes.size() < period + 1) return null;
        double gain = 0, loss = 0;
        for (int i = 1; i <= period; i++) {
            double ch = closes.get(i) - closes.get(i - 1);
            if (ch > 0) gain += ch; else loss -= ch;
        }
        gain /= period;
        loss /= period;
        for (int i = period + 1; i < closes.size(); i++) {
            double ch = closes.get(i) - closes.get(i - 1);
            gain = (gain * (period - 1) + Math.max(ch, 0)) / period;
            loss = (loss * (period - 1) + Math.max(-ch, 0)) / period;
        }
        if (loss == 0) return gain == 0 ? 50.0 : 100.0;
        return 100.0 - 100.0 / (1 + gain / loss);
    }

    /**
     * MACD(fast, slow, signal): MACD line = EMA(fast) − EMA(slow) on the same bars; signal line
     * = EMA(signal) of the MACD line; histogram = line − signal. Needs slow + signal − 1 closes.
     * @return {line, signal, histogram} or null
     */
    public static double[] macd(List<Double> closes, int fast, int slow, int signal) {
        if (closes.size() < slow + signal - 1) return null;
        List<Double> f = emaSeries(closes, fast), s = emaSeries(closes, slow);
        List<Double> line = new ArrayList<>(s.size());
        int offset = f.size() - s.size();
        for (int i = 0; i < s.size(); i++) line.add(f.get(i + offset) - s.get(i));
        List<Double> sig = emaSeries(line, signal);
        if (sig.isEmpty()) return null;
        double l = line.get(line.size() - 1), g = sig.get(sig.size() - 1);
        return new double[]{l, g, l - g};
    }

    /** Bollinger(n, k): SMA(n) ± k·population standard deviation of the same n closes. {upper, middle, lower}. */
    public static double[] bollinger(List<Double> closes, int period, double k) {
        Double mid = sma(closes, period);
        if (mid == null) return null;
        double var = 0;
        for (int i = closes.size() - period; i < closes.size(); i++) var += Math.pow(closes.get(i) - mid, 2);
        double sd = Math.sqrt(var / period);
        return new double[]{mid + k * sd, mid, mid - k * sd};
    }

    /** True range of bar i: max(high − low, |high − prev close|, |low − prev close|). */
    static double trueRange(List<PriceHistory> bars, int i) {
        double h = bars.get(i).getHigh().doubleValue(), l = bars.get(i).getLow().doubleValue();
        double pc = bars.get(i - 1).getClose().doubleValue();
        return Math.max(h - l, Math.max(Math.abs(h - pc), Math.abs(l - pc)));
    }

    /**
     * Wilder ATR through the latest bar: the first value is the mean of the first n true ranges,
     * then atr = (atr·(n−1) + tr)/n. In price units, not a percentage. Needs n+1 bars.
     */
    public static Double atr(List<PriceHistory> bars, int period) {
        if (period <= 0 || bars.size() < period + 1) return null;
        double atr = 0;
        for (int i = 1; i < bars.size(); i++) {
            double tr = trueRange(bars, i);
            atr = i <= period ? atr + tr / period : (atr * (period - 1) + tr) / period;
        }
        return atr;
    }

    /**
     * Wilder ADX(n) with +DI/−DI. +DM = up-move when it exceeds the down-move and is positive
     * (−DM likewise); TR, +DM and −DM are Wilder-smoothed (first value = sum of n, then
     * S − S/n + x); DX = 100·|+DI − −DI|/(+DI + −DI); ADX = the Wilder average of DX, seeded
     * with the mean of the first n DX values. Needs 2n + 1 bars.
     * @return {adx, plusDi, minusDi} or null
     */
    public static double[] adx(List<PriceHistory> bars, int period) {
        if (period <= 0 || bars.size() < 2 * period + 1) return null;
        double tr = 0, pdm = 0, mdm = 0, adx = 0, pdi = 0, mdi = 0;
        int dxCount = 0;
        for (int i = 1; i < bars.size(); i++) {
            double up = bars.get(i).getHigh().doubleValue() - bars.get(i - 1).getHigh().doubleValue();
            double down = bars.get(i - 1).getLow().doubleValue() - bars.get(i).getLow().doubleValue();
            double p = up > down && up > 0 ? up : 0;
            double m = down > up && down > 0 ? down : 0;
            double t = trueRange(bars, i);
            if (i <= period) {
                tr += t; pdm += p; mdm += m;
                if (i < period) continue;
            } else {
                tr = tr - tr / period + t;
                pdm = pdm - pdm / period + p;
                mdm = mdm - mdm / period + m;
            }
            pdi = tr > 0 ? 100 * pdm / tr : 0;
            mdi = tr > 0 ? 100 * mdm / tr : 0;
            double dx = pdi + mdi > 0 ? 100 * Math.abs(pdi - mdi) / (pdi + mdi) : 0;
            dxCount++;
            if (dxCount <= period) {
                adx += dx / period;
            } else {
                adx = (adx * (period - 1) + dx) / period;
            }
        }
        return dxCount >= period ? new double[]{adx, pdi, mdi} : null;
    }

    /** Daily log returns over the last {@code window} changes. */
    public static List<Double> logReturns(List<Double> closes, int window) {
        List<Double> r = new ArrayList<>();
        for (int i = Math.max(1, closes.size() - window); i < closes.size(); i++) {
            r.add(Math.log(closes.get(i) / closes.get(i - 1)));
        }
        return r;
    }

    /** Sample standard deviation (n − 1). Null with fewer than 2 values. */
    public static Double stdev(List<Double> v) {
        if (v.size() < 2) return null;
        double mean = v.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double ss = 0;
        for (double x : v) ss += (x - mean) * (x - mean);
        return Math.sqrt(ss / (v.size() - 1));
    }
}
