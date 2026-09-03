package com.marketai.technical;

import com.marketai.market.entity.PriceHistory;
import com.marketai.market.repository.PriceHistoryRepository;
import com.marketai.market.service.MarketDataService;
import com.marketai.technical.dto.TechnicalAnalysisDto;
import com.marketai.technical.service.TechnicalIndicatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Guards the "never present unverifiable data as analysis" rule for the technical layer.
 *
 * Before these fixes the service silently synthesised a full indicator set from a single
 * live quote whenever price history was thin, and reported a 0-valued SMA200 as a real
 * average (making every short-history symbol read as trading above its 200-DMA).
 */
class TechnicalDataQualityTest {

    private PriceHistoryRepository repo;
    private MarketDataService marketData;
    private TechnicalIndicatorService service;

    @BeforeEach
    void setup() {
        repo = mock(PriceHistoryRepository.class);
        marketData = mock(MarketDataService.class);
        service = new TechnicalIndicatorService(repo, marketData);
    }

    /** Descending-date list, matching findTop200BySymbolOrderByDateDesc. */
    private List<PriceHistory> bars(String symbol, double startPrice, double dailyDelta, int count) {
        List<PriceHistory> out = new ArrayList<>();
        LocalDate day = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < count; i++) {
            double close = startPrice + dailyDelta * i;
            out.add(PriceHistory.builder()
                .symbol(symbol)
                .date(day.plusDays(i))
                .open(BigDecimal.valueOf(close))
                .high(BigDecimal.valueOf(close * 1.01))
                .low(BigDecimal.valueOf(close * 0.99))
                .close(BigDecimal.valueOf(close))
                .adjClose(BigDecimal.valueOf(close))
                .volume(100000L)
                .build());
        }
        Collections.reverse(out); // repository returns newest-first
        return out;
    }

    @Test
    @DisplayName("No usable history → INSUFFICIENT, no invented indicators, no BUY/SELL signal")
    void insufficientHistory_returnsNoIndicators() {
        when(repo.findTop200BySymbolOrderByDateDesc(anyString()))
            .thenReturn(bars("NEWCO.NS", 100, 1, 2)); // only 2 bars, below the 5-bar floor

        TechnicalAnalysisDto ta = service.analyse("NEWCO.NS");

        assertThat(ta.getDataQuality()).isEqualTo("INSUFFICIENT");
        assertThat(ta.getBarsAvailable()).isEqualTo(2);
        assertThat(ta.getSignal()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(ta.getTrend()).isEqualTo("UNKNOWN");
        // Every derived number must be absent rather than fabricated.
        assertThat(ta.getRsi()).isNull();
        assertThat(ta.getMacd()).isNull();
        assertThat(ta.getSma20()).isNull();
        assertThat(ta.getSma50()).isNull();
        assertThat(ta.getSma200()).isNull();
        assertThat(ta.getAtr()).isNull();
        assertThat(ta.getBollingerUpper()).isNull();
        assertThat(ta.getSupport()).isNull();
        assertThat(ta.getResistance()).isNull();
    }

    @Test
    @DisplayName("Under 200 bars → PARTIAL and SMA200 is null, not zero")
    void partialHistory_sma200IsNullNotZero() {
        when(repo.findTop200BySymbolOrderByDateDesc(anyString()))
            .thenReturn(bars("MIDCO.NS", 100, 0.5, 60));

        TechnicalAnalysisDto ta = service.analyse("MIDCO.NS");

        assertThat(ta.getDataQuality()).isEqualTo("PARTIAL");
        assertThat(ta.getBarsAvailable()).isEqualTo(60);
        // The load-bearing assertion: a non-computable 200-day average must not surface as 0,
        // because `price > 0` then reads as "trading above the 200-DMA" for every such symbol.
        assertThat(ta.getSma200()).isNull();
        assertThat(ta.getSma20()).isNotNull();
        assertThat(ta.getRsi()).isNotNull();
    }

    @Test
    @DisplayName("Falling series with full history yields STRONG_DOWNTREND (previously unreachable)")
    void strongDowntrend_isReachable() {
        // Steadily declining prices: last close sits below SMA20/50/200.
        when(repo.findTop200BySymbolOrderByDateDesc(anyString()))
            .thenReturn(bars("FALLCO.NS", 500, -1.0, 220));

        TechnicalAnalysisDto ta = service.analyse("FALLCO.NS");

        assertThat(ta.getDataQuality()).isEqualTo("FULL");
        assertThat(ta.getSma200()).isNotNull();
        // The stricter case used to be shadowed by a looser check placed above it, so this
        // never fired — and it is the recommendation engine's only unconditional EXIT trigger.
        assertThat(ta.getTrend()).isEqualTo("STRONG_DOWNTREND");
    }

    @Test
    @DisplayName("Rising series with full history yields STRONG_UPTREND")
    void strongUptrend_stillWorks() {
        when(repo.findTop200BySymbolOrderByDateDesc(anyString()))
            .thenReturn(bars("RISECO.NS", 100, 1.0, 220));

        TechnicalAnalysisDto ta = service.analyse("RISECO.NS");

        assertThat(ta.getDataQuality()).isEqualTo("FULL");
        assertThat(ta.getTrend()).isEqualTo("STRONG_UPTREND");
    }

    @Test
    @DisplayName("Short falling series is NOT reported as an uptrend just because SMA200 is missing")
    void partialHistory_doesNotFakeUptrend() {
        // 60 falling bars: price is below SMA20 and SMA50, and the 200-DMA is unknown.
        // The old code compared price > 0 for the 200-DMA leg and could classify this UPTREND.
        when(repo.findTop200BySymbolOrderByDateDesc(anyString()))
            .thenReturn(bars("SHORTFALL.NS", 300, -1.0, 60));

        TechnicalAnalysisDto ta = service.analyse("SHORTFALL.NS");

        assertThat(ta.getDataQuality()).isEqualTo("PARTIAL");
        assertThat(ta.getTrend()).isNotIn("UPTREND", "STRONG_UPTREND");
    }
}
