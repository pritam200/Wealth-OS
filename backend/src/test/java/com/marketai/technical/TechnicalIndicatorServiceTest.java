package com.marketai.technical;

import com.marketai.market.entity.PriceHistory;
import com.marketai.market.quality.DailySeries;
import com.marketai.market.quality.PriceSeriesValidator;
import com.marketai.market.service.MarketDataService;
import com.marketai.support.Bars;
import com.marketai.technical.dto.IndicatorValue;
import com.marketai.technical.dto.TechnicalAnalysisDto;
import com.marketai.technical.service.TechnicalIndicatorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** The canonical technical read: nothing invented below a data requirement, everything traceable above it. */
class TechnicalIndicatorServiceTest {

    private final TechnicalIndicatorService service = new TechnicalIndicatorService(mock(MarketDataService.class));

    private static DailySeries series(List<PriceHistory> bars) {
        LocalDate last = bars.isEmpty() ? LocalDate.of(2026, 9, 30) : bars.get(bars.size() - 1).getDate();
        return PriceSeriesValidator.validate("TEST", bars, last, TechnicalIndicatorService.MIN_BARS);
    }

    private static List<PriceHistory> trend(double start, double step, int n) {
        List<PriceHistory> out = new ArrayList<>();
        List<LocalDate> d = Bars.weekdaysEndingOn(LocalDate.of(2026, 9, 30), n);
        for (int i = 0; i < n; i++) {
            double c = start + step * i + (i % 3 == 0 ? step * 0.4 : -step * 0.2); // small zig-zag so swings exist
            out.add(Bars.bar(d.get(i), c, c * 1.01, c * 0.99, c, 100_000));
        }
        return out;
    }

    @Test
    @DisplayName("Under 20 valid bars: INSUFFICIENT, no indicator values at all")
    void insufficient() {
        TechnicalAnalysisDto ta = service.analyse(series(trend(100, 1, 10)));
        assertThat(ta.getDataQuality()).isEqualTo("INSUFFICIENT");
        assertThat(ta.getSeriesStatus()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(ta.getTrend()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(ta.getRsi()).isNull();
        assertThat(ta.getSma20()).isNull();
        assertThat(ta.getAtr()).isNull();
        assertThat(ta.getSupport()).isNull();
    }

    @Test
    @DisplayName("Under 200 bars: PARTIAL, SMA200 null (not 0), and a short fall is not called an uptrend")
    void partial() {
        TechnicalAnalysisDto ta = service.analyse(series(trend(300, -1, 60)));
        assertThat(ta.getDataQuality()).isEqualTo("PARTIAL");
        assertThat(ta.getSma200()).isNull();
        assertThat(ta.getEma200()).isNull();
        assertThat(ta.getSma20()).isNotNull();
        assertThat(ta.getTrend()).doesNotContain("UPTREND");
        IndicatorValue sma200 = ta.getIndicators().stream().filter(i -> i.getKey().equals("SMA200")).findFirst().orElseThrow();
        assertThat(sma200.isAvailable()).isFalse();
        assertThat(sma200.getReason()).contains("Needs 200");
    }

    @Test
    @DisplayName("Steady rise and fall classify as STRONG_UPTREND / STRONG_DOWNTREND with the evidence listed")
    void trends() {
        TechnicalAnalysisDto up = service.analyse(series(trend(100, 1, 450)));
        assertThat(up.getTrend()).isEqualTo("STRONG_UPTREND");
        assertThat(up.getTrendAssessment().getEvidence()).extracting("name").contains("Price vs SMA200", "SMA50 slope", "ADX(14)");
        TechnicalAnalysisDto down = service.analyse(series(trend(1000, -1, 450)));
        assertThat(down.getTrend()).isEqualTo("STRONG_DOWNTREND");
    }

    @Test
    @DisplayName("Real data: every indicator carries formula, period, timeframe and as-of date")
    void metadata() {
        TechnicalAnalysisDto ta = service.analyse(series(Bars.fixture("reliance_daily.csv")));
        assertThat(ta.getIndicators()).isNotEmpty().allSatisfy(i -> {
            assertThat(i.getFormula()).isNotBlank();
            assertThat(i.getTimeframe()).isEqualTo("1D");
            assertThat(i.getAsOf()).isEqualTo(LocalDate.of(2026, 9, 30));
            assertThat(i.isAvailable()).isTrue();
        });
        assertThat(ta.getAtr()).isEqualByComparingTo(new BigDecimal("19.61"));
        assertThat(ta.getLastBarDate()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    @DisplayName("Support is never simply the current price, even at a fresh low")
    void supportIsNotThePrice() {
        TechnicalAnalysisDto ta = service.analyse(series(Bars.fixture("reliance_daily.csv")));
        if (ta.getSupport() != null) assertThat(ta.getSupport()).isLessThan(ta.getPrice());
        if (ta.getResistance() != null) assertThat(ta.getResistance()).isGreaterThan(ta.getPrice());
        // A series that closes at its lowest point has no support below it — reported as such.
        List<PriceHistory> falling = new ArrayList<>();
        List<LocalDate> d = Bars.weekdaysEndingOn(LocalDate.of(2026, 9, 30), 300);
        for (int i = 0; i < 300; i++) { double c = 1000 - i; falling.add(Bars.bar(d.get(i), c + 0.5, c + 1, c - 0.5, c, 1000)); }
        TechnicalAnalysisDto low = service.analyse(series(falling));
        assertThat(low.getLevels().getSupportStatus()).isEqualTo("NO_RELIABLE_LEVEL");
        assertThat(low.getSupport()).isNull();
    }

    @Test
    @DisplayName("Missing volume leaves volume measures null with a reason instead of zeros")
    void zeroVolume() {
        List<PriceHistory> bars = trend(100, 1, 80);
        bars.forEach(b -> b.setVolume(0L));
        TechnicalAnalysisDto ta = service.analyse(series(bars));
        assertThat(ta.getVolume().getRelativeVolume()).isNull();
        assertThat(ta.getVolume().getReason()).isNotBlank();
        assertThat(ta.getDataIssues()).extracting("code").contains("VOLUME_UNAVAILABLE");
    }
}
