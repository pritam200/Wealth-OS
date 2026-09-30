package com.marketai.technical.service;

import com.marketai.market.entity.PriceHistory;
import com.marketai.technical.dto.PriceLevel;
import com.marketai.technical.dto.SupportResistance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Support and resistance from actual market structure.
 *
 * Candidate levels:
 *  - SWING_CLUSTER: confirmed swing highs and lows (the extreme of {@value #PIVOT_SPAN} bars on
 *    each side) from the last {@value #LOOKBACK} sessions, merged when they lie within
 *    max(0.5·ATR, 0.5% of price) of each other. A level needs at least {@value #MIN_TOUCHES}
 *    touches — a single swing point is not treated as a level. Former highs can act as
 *    support and vice versa, so highs and lows cluster together.
 *  - 52W_HIGH / 52W_LOW: the extremes of the last 250 sessions.
 *  - SMA50 / SMA200: widely watched dynamic levels.
 *  - GAP: the edge of an unfilled gap from the last 120 sessions.
 * Levels below the price are support, above are resistance; nearest first.
 */
public final class SupportResistanceAnalyzer {

    private SupportResistanceAnalyzer() {}

    static final int PIVOT_SPAN = 3;
    static final int LOOKBACK = 250;
    static final int MIN_TOUCHES = 2;
    static final int GAP_LOOKBACK = 120;
    /** Levels closer than this to the price are "at" the price, not either side of it. */
    static final double AT_PRICE = 0.001;

    public static final String METHOD = "Swing-point clusters (≥2 touches, tolerance max(0.5×ATR14, 0.5% of price), last 250 sessions), "
            + "52-week high/low (once more than 3 sessions old), SMA50/SMA200 and unfilled gaps (last 120 sessions). Nearest level either side of the last close.";

    public static SupportResistance analyse(List<PriceHistory> bars, Double atr, Double sma50, Double sma200) {
        if (bars.size() < 2 * PIVOT_SPAN + 20) {
            return SupportResistance.builder().supportStatus(SupportResistance.NO_RELIABLE_LEVEL)
                    .resistanceStatus(SupportResistance.NO_RELIABLE_LEVEL).method(METHOD).build();
        }
        double price = bars.get(bars.size() - 1).getClose().doubleValue();
        double tol = Math.max(atr != null ? 0.5 * atr : 0, 0.005 * price);
        List<PriceHistory> win = bars.subList(Math.max(0, bars.size() - LOOKBACK), bars.size());

        // Swing points: {price, date}
        List<Object[]> pivots = new ArrayList<>();
        for (int i = PIVOT_SPAN; i < win.size() - PIVOT_SPAN; i++) {
            double hi = win.get(i).getHigh().doubleValue(), lo = win.get(i).getLow().doubleValue();
            boolean ph = true, pl = true;
            for (int k = i - PIVOT_SPAN; k <= i + PIVOT_SPAN; k++) {
                if (k == i) continue;
                if (win.get(k).getHigh().doubleValue() >= hi) ph = false;
                if (win.get(k).getLow().doubleValue() <= lo) pl = false;
            }
            if (ph) pivots.add(new Object[]{hi, win.get(i).getDate()});
            if (pl) pivots.add(new Object[]{lo, win.get(i).getDate()});
        }
        pivots.sort(Comparator.comparingDouble(o -> (double) o[0]));

        List<PriceLevel> levels = new ArrayList<>();
        int i = 0;
        while (i < pivots.size()) {
            int j = i;
            double sum = 0;
            LocalDate last = null;
            double start = (double) pivots.get(i)[0];
            while (j < pivots.size() && (double) pivots.get(j)[0] - start <= 2 * tol) {
                sum += (double) pivots.get(j)[0];
                LocalDate d = (LocalDate) pivots.get(j)[1];
                if (last == null || d.isAfter(last)) last = d;
                j++;
            }
            int touches = j - i;
            if (touches >= MIN_TOUCHES) {
                double lvl = sum / touches;
                levels.add(level(lvl, price, "SWING_CLUSTER", touches, last,
                        touches + " swing highs/lows within ±" + fmt(tol) + " of " + fmt(lvl) + ", last on " + last));
            }
            i = j;
        }

        List<PriceHistory> year = bars.subList(Math.max(0, bars.size() - 250), bars.size());
        PriceHistory hiBar = year.stream().max(Comparator.comparing(PriceHistory::getHigh)).orElseThrow();
        PriceHistory loBar = year.stream().min(Comparator.comparing(PriceHistory::getLow)).orElseThrow();
        String span = year.size() >= 250 ? "52-week" : year.size() + "-session";
        // An extreme set in the last few sessions is where the price is now, not a level it has
        // come back to — the same confirmation lag the swing points need.
        LocalDate confirmed = bars.get(bars.size() - 1 - PIVOT_SPAN).getDate();
        if (!hiBar.getDate().isAfter(confirmed)) {
            levels.add(level(hiBar.getHigh().doubleValue(), price, "52W_HIGH", 0, hiBar.getDate(), span + " high on " + hiBar.getDate()));
        }
        if (!loBar.getDate().isAfter(confirmed)) {
            levels.add(level(loBar.getLow().doubleValue(), price, "52W_LOW", 0, loBar.getDate(), span + " low on " + loBar.getDate()));
        }
        if (sma50 != null) levels.add(level(sma50, price, "SMA50", 0, null, "50-session simple moving average"));
        if (sma200 != null) levels.add(level(sma200, price, "SMA200", 0, null, "200-session simple moving average"));

        // Unfilled gaps: a gap up leaves support at the prior high until a later low trades back
        // through it; a gap down leaves resistance at the prior low.
        int g0 = Math.max(1, bars.size() - GAP_LOOKBACK);
        for (int k = g0; k < bars.size(); k++) {
            double prevHigh = bars.get(k - 1).getHigh().doubleValue(), prevLow = bars.get(k - 1).getLow().doubleValue();
            double lo = bars.get(k).getLow().doubleValue(), hi = bars.get(k).getHigh().doubleValue();
            if (lo > prevHigh && lo - prevHigh > tol * 0.5) {
                boolean filled = false;
                for (int m = k + 1; m < bars.size() && !filled; m++) filled = bars.get(m).getLow().doubleValue() <= prevHigh;
                if (!filled) levels.add(level(prevHigh, price, "GAP", 0, bars.get(k).getDate(),
                        "Unfilled gap up on " + bars.get(k).getDate() + " (" + fmt(prevHigh) + "–" + fmt(lo) + ")"));
            } else if (hi < prevLow && prevLow - hi > tol * 0.5) {
                boolean filled = false;
                for (int m = k + 1; m < bars.size() && !filled; m++) filled = bars.get(m).getHigh().doubleValue() >= prevLow;
                if (!filled) levels.add(level(prevLow, price, "GAP", 0, bars.get(k).getDate(),
                        "Unfilled gap down on " + bars.get(k).getDate() + " (" + fmt(hi) + "–" + fmt(prevLow) + ")"));
            }
        }

        List<PriceLevel> below = merge(levels.stream().filter(l -> l.getPrice().doubleValue() < price * (1 - AT_PRICE))
                .sorted(Comparator.comparing(PriceLevel::getPrice).reversed()).toList(), tol);
        List<PriceLevel> above = merge(levels.stream().filter(l -> l.getPrice().doubleValue() > price * (1 + AT_PRICE))
                .sorted(Comparator.comparing(PriceLevel::getPrice)).toList(), tol);

        return SupportResistance.builder()
                .nearestSupport(below.isEmpty() ? null : below.get(0))
                .nextSupport(below.size() > 1 ? below.get(1) : null)
                .nearestResistance(above.isEmpty() ? null : above.get(0))
                .nextResistance(above.size() > 1 ? above.get(1) : null)
                .supportStatus(below.isEmpty() ? SupportResistance.NO_RELIABLE_LEVEL : SupportResistance.OK)
                .resistanceStatus(above.isEmpty() ? SupportResistance.NO_RELIABLE_LEVEL : SupportResistance.OK)
                .method(METHOD)
                .build();
    }

    /** Levels from different sources within the tolerance are one zone; keep the one with most evidence, list the rest in its reason. */
    private static List<PriceLevel> merge(List<PriceLevel> sortedNearestFirst, double tol) {
        List<PriceLevel> out = new ArrayList<>();
        for (PriceLevel l : sortedNearestFirst) {
            PriceLevel prev = out.isEmpty() ? null : out.get(out.size() - 1);
            if (prev != null && Math.abs(prev.getPrice().doubleValue() - l.getPrice().doubleValue()) <= tol) {
                PriceLevel keep = rank(l) > rank(prev) ? l : prev, other = keep == l ? prev : l;
                keep.setReason(keep.getReason() + "; coincides with " + other.getSource() + " " + other.getPrice());
                out.set(out.size() - 1, keep);
            } else {
                out.add(l);
            }
        }
        return out;
    }

    private static int rank(PriceLevel l) {
        return switch (l.getSource()) {
            case "SWING_CLUSTER" -> 10 + l.getTouches();
            case "52W_HIGH", "52W_LOW" -> 8;
            case "SMA200" -> 6;
            case "GAP" -> 5;
            default -> 4;
        };
    }

    private static PriceLevel level(double lvl, double price, String source, int touches, LocalDate last, String reason) {
        return PriceLevel.builder()
                .price(BigDecimal.valueOf(lvl).setScale(2, RoundingMode.HALF_UP))
                .source(source).touches(touches).lastTouched(last)
                .distancePct(BigDecimal.valueOf((lvl / price - 1) * 100).setScale(2, RoundingMode.HALF_UP))
                .reason(reason).build();
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
