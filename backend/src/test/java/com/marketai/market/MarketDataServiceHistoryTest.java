package com.marketai.market;

import com.marketai.market.client.YahooFinanceClient;
import com.marketai.market.entity.PriceHistory;
import com.marketai.market.entity.Stock;
import com.marketai.market.quality.DailySeries;
import com.marketai.market.repository.MarketIndexRepository;
import com.marketai.market.repository.PriceHistoryRepository;
import com.marketai.market.repository.StockRepository;
import com.marketai.market.service.MarketDataService;
import com.marketai.support.Bars;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** One stored history per instrument, read without duplicates, written by one writer at a time. */
class MarketDataServiceHistoryTest {

    private final StockRepository stocks = mock(StockRepository.class);
    private final PriceHistoryRepository history = mock(PriceHistoryRepository.class);
    private final YahooFinanceClient yahoo = mock(YahooFinanceClient.class);
    private final MarketDataService service = new MarketDataService(stocks, history, mock(MarketIndexRepository.class), yahoo);

    private static PriceHistory bar(String symbol, LocalDate d, double close, LocalDateTime updated, Long id) {
        PriceHistory p = Bars.bar(d, close, close, close, close, 1000);
        p.setSymbol(symbol);
        p.setUpdatedAt(updated);
        p.setId(id);
        return p;
    }

    @Test
    @DisplayName("NSE is the default listing: RELIANCE and RELIANCE.NS are one symbol; BSE and indices keep their names")
    void canonicalNames() {
        assertThat(MarketDataService.canonicalSymbol("reliance.ns")).isEqualTo("RELIANCE");
        assertThat(MarketDataService.canonicalSymbol("RELIANCE")).isEqualTo("RELIANCE");
        assertThat(MarketDataService.canonicalSymbol("ABC.BO")).isEqualTo("ABC.BO");
        assertThat(MarketDataService.canonicalSymbol("NIFTY50")).isEqualTo("^NSEI");
        assertThat(MarketDataService.storedNames("RELIANCE")).containsExactly("RELIANCE", "RELIANCE.NS");
        assertThat(MarketDataService.storedNames("^NSEI")).containsExactly("^NSEI");
        assertThat(MarketDataService.storedNames("ABC.BO")).containsExactly("ABC.BO");
    }

    @Test
    @DisplayName("The price chart gets one bar per date — the most recently written copy — across old and new names")
    void chartCollapsesDuplicates() {
        LocalDate d1 = LocalDate.of(2026, 9, 28), d2 = LocalDate.of(2026, 9, 29);
        LocalDateTime old = LocalDateTime.of(2026, 9, 1, 0, 0), recent = LocalDateTime.of(2026, 9, 30, 0, 0);
        when(history.findDailyBySymbolsAndDateBetween(eq(List.of("RELIANCE", "RELIANCE.NS")), any(), any())).thenReturn(List.of(
                bar("RELIANCE.NS", d2, 200, recent, 5L),
                bar("RELIANCE.NS", d1, 100, old, 1L),
                bar("RELIANCE", d1, 110, recent, 9L),
                bar("RELIANCE.NS", d2, 150, old, 2L)));

        List<PriceHistory> chart = service.getPriceHistory("RELIANCE.NS", d1, d2);

        assertThat(chart).extracting(PriceHistory::getDate).containsExactly(d1, d2);
        assertThat(chart).extracting(p -> p.getClose().doubleValue()).containsExactly(110.0, 200.0);
    }

    @Test
    @DisplayName("Duplicate rows no longer use up the series allowance: the history is read by date, then collapsed")
    void seriesIsNotShortenedByDuplicates() {
        LocalDate today = LocalDate.now().plusDays(10); // newer than any expected session: no fetch
        List<PriceHistory> rows = new ArrayList<>();
        for (PriceHistory p : Bars.randomWalk(300, 0.01, 3, today)) {
            p.setSymbol("TCS");
            rows.add(p);
            PriceHistory copy = Bars.bar(p.getDate(), p.getOpen().doubleValue(), p.getHigh().doubleValue(),
                    p.getLow().doubleValue(), p.getClose().doubleValue(), p.getVolume());
            copy.setSymbol("TCS");
            rows.add(copy);
        }
        when(history.findNewestDaily(eq(List.of("TCS", "TCS.NS")), any())).thenReturn(List.of(rows.get(rows.size() - 1)));
        when(history.findDailyBySymbolsAndDateBetween(eq(List.of("TCS", "TCS.NS")), any(), any())).thenReturn(rows);

        DailySeries s = service.getDailySeries("TCS", 20);

        // Every date stored, once each (bars after the latest closed session are held back as unsettled).
        long dates = rows.stream().map(PriceHistory::getDate).filter(d -> !d.isAfter(s.expectedSession())).distinct().count();
        assertThat(dates).isGreaterThan(280);
        assertThat(s.size()).isEqualTo((int) dates);
        // One line for all 300 duplicates, not 300 lines.
        assertThat(s.issues()).filteredOn(i -> i.code().equals("DUPLICATE_CANDLE")).hasSize(1);
        verifyNoInteractions(yahoo);
    }

    @Test
    @DisplayName("Funds have no exchange feed: their history is never requested from Yahoo")
    void fundsAreNotFetched() {
        when(history.findNewestDaily(any(), any())).thenReturn(List.of());
        assertThat(service.ensureFreshDailyHistory("HDFCFLEXI.MF")).isEqualTo(MarketDataService.Freshness.SKIPPED);
        verifyNoInteractions(yahoo);
    }

    @Test
    @DisplayName("A fetch that finds nothing new (a holiday) isn't repeated on every request for that session")
    void emptyFetchIsNotRepeated() {
        when(history.findNewestDaily(any(), any())).thenReturn(List.of());
        when(yahoo.getHistory(anyString(), anyString(), anyString())).thenReturn(List.of());

        assertThat(service.ensureFreshDailyHistory("HOLIDAYCO")).isEqualTo(MarketDataService.Freshness.NOTHING_NEW);
        assertThat(service.ensureFreshDailyHistory("HOLIDAYCO")).isEqualTo(MarketDataService.Freshness.SKIPPED);
        assertThat(service.ensureFreshDailyHistory("HOLIDAYCO.NS")).isEqualTo(MarketDataService.Freshness.SKIPPED);
        verify(yahoo, times(1)).getHistory(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("The manual fetch writes under the canonical name, and rewrites a legacy .NS bar in place instead of adding a second")
    @SuppressWarnings("unchecked")
    void manualFetchIsCanonicalAndUpdatesInPlace() {
        LocalDate d = LocalDate.of(2024, 3, 4); // a settled past session
        PriceHistory legacy = bar("INFY.NS", d, 100, LocalDateTime.of(2024, 3, 4, 18, 0), 7L);
        when(history.findDailyBySymbolsAndDateBetween(eq(List.of("INFY", "INFY.NS")), any(), any())).thenReturn(List.of(legacy));
        when(stocks.findBySymbol(anyString())).thenReturn(Optional.empty());
        when(yahoo.getHistory(eq("INFY.NS"), eq("1y"), eq("1d"))).thenReturn(List.of(
                new YahooFinanceClient.OhlcvBar(d, 101, 102, 99, 101.5, 5000),
                new YahooFinanceClient.OhlcvBar(d.plusDays(1), 102, 103, 100, 102.5, 6000)));

        service.fetchAndStorePriceHistory("infy.ns", "1y");

        ArgumentCaptor<List<PriceHistory>> saved = ArgumentCaptor.forClass(List.class);
        verify(history).saveAll(saved.capture());
        List<PriceHistory> rows = saved.getValue();
        assertThat(rows).hasSize(2);
        assertThat(rows).filteredOn(r -> r.getId() != null).singleElement()
                .satisfies(r -> {
                    assertThat(r.getSymbol()).isEqualTo("INFY.NS");
                    assertThat(r.getClose()).isEqualByComparingTo(new BigDecimal("101.50"));
                });
        assertThat(rows).filteredOn(r -> r.getId() == null).singleElement()
                .satisfies(r -> assertThat(r.getSymbol()).isEqualTo("INFY"));
    }

    @Test
    @DisplayName("A stock row marked inactive reads as possibly suspended or delisted")
    void inactiveStock() {
        Stock st = Stock.builder().symbol("GONE").name("Gone Ltd").build();
        st.setActive(false);
        when(stocks.findBySymbol("GONE")).thenReturn(Optional.of(st));
        assertThat(service.isInactive("GONE.NS")).isTrue();
        when(stocks.findBySymbol("LIVE")).thenReturn(Optional.empty());
        assertThat(service.isInactive("LIVE")).isFalse();
    }

    @Test
    @DisplayName("A series marked as still downloading says so")
    void pendingMarker() {
        DailySeries empty = com.marketai.market.quality.PriceSeriesValidator.validate("X", List.of(), LocalDate.of(2026, 9, 29), 20);
        assertThat(empty.pending()).isFalse();
        DailySeries pending = empty.withIssue(new com.marketai.market.quality.DataIssue(DailySeries.HISTORY_PENDING, "INFO", null, "loading"));
        assertThat(pending.pending()).isTrue();
        assertThat(pending.status()).isEqualTo(com.marketai.market.quality.SeriesStatus.INSUFFICIENT_DATA);
    }
}
