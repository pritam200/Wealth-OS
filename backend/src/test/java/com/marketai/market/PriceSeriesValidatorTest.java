package com.marketai.market;

import com.marketai.market.entity.PriceHistory;
import com.marketai.market.quality.DailySeries;
import com.marketai.market.quality.PriceSeriesValidator;
import com.marketai.market.quality.SeriesStatus;
import com.marketai.market.service.MarketDataService;
import com.marketai.support.Bars;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Corrupt, stale and incomplete market data is excluded or flagged — never silently used. */
class PriceSeriesValidatorTest {

    private static final LocalDate LAST = LocalDate.of(2026, 9, 29); // a Tuesday

    private static List<PriceHistory> walk(int n) {
        return new ArrayList<>(Bars.randomWalk(n, 0.01, 7, LAST));
    }

    private static DailySeries validate(List<PriceHistory> rows, LocalDate expected) {
        return PriceSeriesValidator.validate("TEST", rows, expected, 20);
    }

    private static void scale(PriceHistory b, BigDecimal f) {
        b.setOpen(b.getOpen().multiply(f)); b.setHigh(b.getHigh().multiply(f));
        b.setLow(b.getLow().multiply(f)); b.setClose(b.getClose().multiply(f));
    }

    @Test
    @DisplayName("Clean current data is OK and comes back oldest first even when stored newest first")
    void clean() {
        List<PriceHistory> rows = walk(60);
        Collections.reverse(rows);
        DailySeries s = validate(rows, LAST);
        assertThat(s.status()).isEqualTo(SeriesStatus.OK);
        assertThat(s.bars().get(0).getDate()).isBefore(s.last().getDate());
        assertThat(s.lastBarDate()).isEqualTo(LAST);
    }

    @Test
    @DisplayName("Zero, inverted and out-of-range OHLC bars are excluded and reported")
    void invalidOhlc() {
        List<PriceHistory> rows = walk(60);
        rows.get(10).setClose(BigDecimal.ZERO);
        rows.get(20).setLow(rows.get(20).getHigh().add(BigDecimal.TEN));
        rows.get(30).setClose(rows.get(30).getHigh().multiply(new BigDecimal("1.05")));
        DailySeries s = validate(rows, LAST);
        assertThat(s.size()).isEqualTo(57);
        assertThat(s.rejected()).isEqualTo(3);
        assertThat(s.issues()).filteredOn(i -> i.code().equals("INVALID_OHLC")).hasSize(3);
        assertThat(s.status()).isEqualTo(SeriesStatus.DATA_QUALITY_WARNING);
    }

    @Test
    @DisplayName("Duplicate candles collapse to one bar per date, keeping the most recently updated")
    void duplicates() {
        List<PriceHistory> rows = walk(40);
        PriceHistory dup = Bars.bar(LAST, 1, 2, 0.5, 1.5, 10);
        dup.setUpdatedAt(java.time.LocalDateTime.now());
        rows.get(rows.size() - 1).setUpdatedAt(java.time.LocalDateTime.now().minusDays(1));
        rows.add(dup);
        DailySeries s = validate(rows, LAST);
        assertThat(s.size()).isEqualTo(40);
        assertThat(s.last().getClose()).isEqualByComparingTo("1.50");
        assertThat(s.issues()).extracting("code").contains("DUPLICATE_CANDLE");
    }

    @Test
    @DisplayName("A bar for a session that has not closed is excluded")
    void unfinishedCandle() {
        List<PriceHistory> rows = walk(40);
        rows.add(Bars.bar(LAST.plusDays(1), 100, 101, 99, 100.5, 5));
        DailySeries s = validate(rows, LAST);
        assertThat(s.lastBarDate()).isEqualTo(LAST);
        assertThat(s.issues()).extracting("code").contains("UNFINISHED_CANDLE");
    }

    @Test
    @DisplayName("Newest bar more than 3 sessions behind is STALE_DATA")
    void stale() {
        DailySeries s = validate(walk(40), LAST.plusDays(7));
        assertThat(s.status()).isEqualTo(SeriesStatus.STALE_DATA);
        assertThat(s.sessionsBehind()).isEqualTo(5);
        assertThat(validate(walk(40), LAST.plusDays(1)).status()).isEqualTo(SeriesStatus.OK);
    }

    @Test
    @DisplayName("Too few bars is INSUFFICIENT_DATA")
    void insufficient() {
        assertThat(validate(walk(10), LAST).status()).isEqualTo(SeriesStatus.INSUFFICIENT_DATA);
        assertThat(validate(List.of(), LAST).status()).isEqualTo(SeriesStatus.INSUFFICIENT_DATA);
    }

    @Test
    @DisplayName("A week-long hole is MISSING_DATES; a one-day market holiday is not")
    void missingDates() {
        List<PriceHistory> rows = walk(60);
        List<PriceHistory> holiday = new ArrayList<>(rows);
        holiday.remove(30);
        assertThat(validate(holiday, LAST).issues()).extracting("code").doesNotContain("MISSING_DATES");
        List<PriceHistory> hole = new ArrayList<>(rows);
        for (int i = 0; i < 6; i++) hole.remove(30);
        assertThat(validate(hole, LAST).issues()).extracting("code").contains("MISSING_DATES");
    }

    @Test
    @DisplayName("A halving of the close is flagged as a possible unadjusted split; a 25% crash as an abnormal move")
    void splitAndAbnormal() {
        List<PriceHistory> rows = walk(60);
        for (int i = 40; i < rows.size(); i++) scale(rows.get(i), new BigDecimal("0.5"));
        DailySeries s = validate(rows, LAST);
        assertThat(s.issues()).extracting("code").contains("POSSIBLE_UNADJUSTED_SPLIT");
        assertThat(s.hasWarningWithin(30, "POSSIBLE_UNADJUSTED_SPLIT")).isTrue();

        List<PriceHistory> crash = walk(60);
        for (int i = 40; i < crash.size(); i++) scale(crash.get(i), new BigDecimal("0.75"));
        assertThat(validate(crash, LAST).issues()).extracting("code").contains("ABNORMAL_MOVE").doesNotContain("POSSIBLE_UNADJUSTED_SPLIT");
    }

    @Test
    @DisplayName("A real 20% intraday fall that opens near the prior close is not mistaken for a 1.25:1 bonus")
    void crashMatchingBonusRatioIsNotASplit() {
        // ADANIENT.NS 3 → 4 Jun 2024: close 3645.25 → 2941.25 (ratio 1.239), opened 3503.
        PriceHistory prev = Bars.bar(LAST.minusDays(1), 3600, 3700, 3580, 3645.25, 1_000_000);
        PriceHistory day  = Bars.bar(LAST, 3503, 3520, 2890, 2941.25, 5_000_000);
        assertThat(PriceSeriesValidator.splitSignature(prev, day)).isNull();
        // The same close ratio with the whole bar rescaled is a split signature.
        PriceHistory split = Bars.bar(LAST, 2890, 2950, 2860, 2941.25, 5_000_000);
        assertThat(PriceSeriesValidator.splitSignature(prev, split)).isEqualTo(1.25);
    }

    @Test
    @DisplayName("Last completed session: before 15:45 IST it is the previous weekday; weekends roll back to Friday")
    void sessionClock() {
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        assertThat(MarketDataService.lastCompletedSession(ZonedDateTime.of(2026, 9, 30, 11, 0, 0, 0, ist))).isEqualTo(LocalDate.of(2026, 9, 29));
        assertThat(MarketDataService.lastCompletedSession(ZonedDateTime.of(2026, 9, 30, 16, 0, 0, 0, ist))).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(MarketDataService.lastCompletedSession(ZonedDateTime.of(2026, 10, 4, 12, 0, 0, 0, ist))).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(MarketDataService.lastCompletedSession(ZonedDateTime.of(2026, 10, 5, 9, 30, 0, 0, ist))).isEqualTo(LocalDate.of(2026, 10, 2));
        // 12:00 UTC is 17:30 IST — the session has closed.
        assertThat(MarketDataService.lastCompletedSession(ZonedDateTime.of(2026, 9, 30, 12, 0, 0, 0, ZoneId.of("UTC")))).isEqualTo(LocalDate.of(2026, 9, 30));
    }
}
