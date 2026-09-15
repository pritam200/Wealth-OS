package com.marketai.signal.service;

import com.marketai.market.entity.PriceHistory;
import lombok.Builder;
import lombok.Data;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects market structure — the sequence of swing highs and lows that defines a trend
 * independently of any oscillator.
 *
 * This is included because it is one of the few factors genuinely uncorrelated with the
 * momentum family (RSI/CCI/Stochastic/MACD-line all measure roughly the same thing, with
 * measured pairwise correlation above 0.9). Structure carries information those do not.
 */
@Service
public class MarketStructureAnalyzer {

    /** Bars on each side that must be lower/higher for a pivot to count as a swing point. */
    private static final int PIVOT_LOOKBACK = 2;

    /** Swing points considered when classifying the trend. */
    private static final int SWINGS_CONSIDERED = 4;

    public enum Structure {
        UPTREND,            // higher highs and higher lows
        DOWNTREND,          // lower highs and lower lows
        RANGING,            // neither sequence holds
        INSUFFICIENT_DATA
    }

    @Data @Builder
    public static class StructureRead {
        private Structure structure;
        /** -100..+100, or null when there was not enough data to judge. */
        private Integer score;
        private String detail;
        private int swingHighs;
        private int swingLows;
    }

    public StructureRead analyse(List<PriceHistory> barsOldestFirst) {
        if (barsOldestFirst == null || barsOldestFirst.size() < PIVOT_LOOKBACK * 2 + 5) {
            return StructureRead.builder()
                .structure(Structure.INSUFFICIENT_DATA).score(null)
                .detail("Not enough bars to identify swing points.")
                .build();
        }

        List<BigDecimal> highs = new ArrayList<>();
        List<BigDecimal> lows = new ArrayList<>();

        // A pivot needs PIVOT_LOOKBACK confirmed bars on BOTH sides, so the scan stops short
        // of the final bars. That lag is deliberate: treating the newest bar as a pivot means
        // reading structure that has not formed yet.
        for (int i = PIVOT_LOOKBACK; i < barsOldestFirst.size() - PIVOT_LOOKBACK; i++) {
            if (isPivotHigh(barsOldestFirst, i)) highs.add(barsOldestFirst.get(i).getHigh());
            if (isPivotLow(barsOldestFirst, i))  lows.add(barsOldestFirst.get(i).getLow());
        }

        if (highs.size() < 2 || lows.size() < 2) {
            return StructureRead.builder()
                .structure(Structure.RANGING).score(0)
                .detail("Too few confirmed swing points to establish a trend.")
                .swingHighs(highs.size()).swingLows(lows.size())
                .build();
        }

        List<BigDecimal> recentHighs = tail(highs, SWINGS_CONSIDERED);
        List<BigDecimal> recentLows  = tail(lows,  SWINGS_CONSIDERED);

        boolean higherHighs = isRising(recentHighs);
        boolean higherLows  = isRising(recentLows);
        boolean lowerHighs  = isFalling(recentHighs);
        boolean lowerLows   = isFalling(recentLows);

        if (higherHighs && higherLows) {
            return build(Structure.UPTREND, 70, "Higher highs and higher lows.", highs, lows);
        }
        if (lowerHighs && lowerLows) {
            return build(Structure.DOWNTREND, -70, "Lower highs and lower lows.", highs, lows);
        }
        // A half-formed structure is real information, but weaker than a confirmed one.
        if (higherLows && !lowerHighs) {
            return build(Structure.UPTREND, 35, "Higher lows, but highs are not yet confirming.", highs, lows);
        }
        if (lowerHighs && !higherLows) {
            return build(Structure.DOWNTREND, -35, "Lower highs, but lows are not yet confirming.", highs, lows);
        }
        return build(Structure.RANGING, 0, "Highs and lows are not trending in agreement.", highs, lows);
    }

    private StructureRead build(Structure s, int score, String detail,
                                List<BigDecimal> highs, List<BigDecimal> lows) {
        return StructureRead.builder()
            .structure(s).score(score).detail(detail)
            .swingHighs(highs.size()).swingLows(lows.size())
            .build();
    }

    private boolean isPivotHigh(List<PriceHistory> bars, int i) {
        BigDecimal h = bars.get(i).getHigh();
        if (h == null) return false;
        for (int j = i - PIVOT_LOOKBACK; j <= i + PIVOT_LOOKBACK; j++) {
            if (j == i) continue;
            BigDecimal other = bars.get(j).getHigh();
            if (other == null || other.compareTo(h) >= 0) return false;
        }
        return true;
    }

    private boolean isPivotLow(List<PriceHistory> bars, int i) {
        BigDecimal l = bars.get(i).getLow();
        if (l == null) return false;
        for (int j = i - PIVOT_LOOKBACK; j <= i + PIVOT_LOOKBACK; j++) {
            if (j == i) continue;
            BigDecimal other = bars.get(j).getLow();
            if (other == null || other.compareTo(l) <= 0) return false;
        }
        return true;
    }

    private static List<BigDecimal> tail(List<BigDecimal> list, int n) {
        return list.size() <= n ? list : list.subList(list.size() - n, list.size());
    }

    private static boolean isRising(List<BigDecimal> v) {
        for (int i = 1; i < v.size(); i++) if (v.get(i).compareTo(v.get(i - 1)) <= 0) return false;
        return v.size() >= 2;
    }

    private static boolean isFalling(List<BigDecimal> v) {
        for (int i = 1; i < v.size(); i++) if (v.get(i).compareTo(v.get(i - 1)) >= 0) return false;
        return v.size() >= 2;
    }
}
