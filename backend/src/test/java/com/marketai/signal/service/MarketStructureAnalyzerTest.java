package com.marketai.signal.service;

import com.marketai.market.entity.PriceHistory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarketStructureAnalyzerTest {

    private final MarketStructureAnalyzer analyzer = new MarketStructureAnalyzer();

    /** Builds bars from (low, high) pairs; close sits midway. */
    private List<PriceHistory> bars(double[][] lowHigh) {
        List<PriceHistory> out = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < lowHigh.length; i++) {
            double lo = lowHigh[i][0], hi = lowHigh[i][1];
            out.add(PriceHistory.builder()
                .symbol("TEST").date(d.plusDays(i))
                .low(BigDecimal.valueOf(lo)).high(BigDecimal.valueOf(hi))
                .open(BigDecimal.valueOf((lo + hi) / 2)).close(BigDecimal.valueOf((lo + hi) / 2))
                .volume(1000L).interval("1d")
                .build());
        }
        return out;
    }

    @Test
    void detectsAnUptrendFromHigherHighsAndHigherLows() {
        // Zig-zag upward so pivots actually form: each swing high and low exceeds the last.
        List<PriceHistory> bars = bars(new double[][]{
            {10,12},{ 9,11},{11,15},{10,13},{ 9,12},   // pivot high at idx 2
            {13,18},{12,16},{11,15},                    // pivot high at idx 5
            {15,21},{14,19},{13,18},
            {18,25},{17,22},{16,21},
        });
        MarketStructureAnalyzer.StructureRead read = analyzer.analyse(bars);
        assertThat(read.getStructure()).isEqualTo(MarketStructureAnalyzer.Structure.UPTREND);
        assertThat(read.getScore()).isGreaterThan(0);
    }

    @Test
    void detectsADowntrendFromLowerHighsAndLowerLows() {
        // The uptrend case reversed: pivot highs 25 -> 21 -> 18 -> 15 and pivot lows
        // 13 -> 11 -> 9, both strictly descending. Values must be distinct or no pivot forms.
        List<PriceHistory> bars = bars(new double[][]{
            {16,21},{17,22},{18,25},
            {13,18},{14,19},{15,21},
            {11,15},{12,16},{13,18},
            { 9,12},{10,13},{11,15},
            { 9,11},{10,12},
        });
        MarketStructureAnalyzer.StructureRead read = analyzer.analyse(bars);
        assertThat(read.getStructure()).isEqualTo(MarketStructureAnalyzer.Structure.DOWNTREND);
        assertThat(read.getScore()).isLessThan(0);
    }

    @Test
    void reportsInsufficientDataRatherThanGuessingATrend() {
        MarketStructureAnalyzer.StructureRead read = analyzer.analyse(bars(new double[][]{{10,12},{11,13}}));
        assertThat(read.getStructure()).isEqualTo(MarketStructureAnalyzer.Structure.INSUFFICIENT_DATA);
        // Null, not 0: "couldn't tell" must not be scored as "neutral".
        assertThat(read.getScore()).isNull();
    }

    @Test
    void handlesNullAndEmptyInput() {
        assertThat(analyzer.analyse(null).getStructure())
            .isEqualTo(MarketStructureAnalyzer.Structure.INSUFFICIENT_DATA);
        assertThat(analyzer.analyse(Collections.emptyList()).getStructure())
            .isEqualTo(MarketStructureAnalyzer.Structure.INSUFFICIENT_DATA);
    }

    @Test
    void flatPriceActionIsRangingNotTrending() {
        List<PriceHistory> bars = bars(new double[][]{
            {10,12},{10,12},{10,12},{10,12},{10,12},
            {10,12},{10,12},{10,12},{10,12},{10,12},
        });
        MarketStructureAnalyzer.StructureRead read = analyzer.analyse(bars);
        assertThat(read.getStructure()).isIn(
            MarketStructureAnalyzer.Structure.RANGING,
            MarketStructureAnalyzer.Structure.INSUFFICIENT_DATA);
    }
}
