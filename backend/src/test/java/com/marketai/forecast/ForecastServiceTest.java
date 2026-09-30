package com.marketai.forecast;

import com.marketai.forecast.dto.ForecastResponse;
import com.marketai.forecast.service.ForecastService;
import com.marketai.market.entity.PriceHistory;
import com.marketai.market.quality.PriceSeriesValidator;
import com.marketai.market.service.MarketDataService;
import com.marketai.support.Bars;
import com.marketai.technical.service.TechnicalIndicatorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ForecastServiceTest {

    private static final LocalDate END = LocalDate.of(2026, 9, 29);

    private ForecastResponse forecast(List<PriceHistory> bars, LocalDate expected, String horizon) {
        MarketDataService md = mock(MarketDataService.class);
        when(md.getDailySeries(anyString(), anyInt())).thenAnswer(inv ->
                PriceSeriesValidator.validate("TEST", bars, expected, inv.getArgument(1)));
        return new ForecastService(md, new TechnicalIndicatorService(md)).forecast("TEST", horizon, null);
    }

    @Test
    @DisplayName("Stale data: STALE_DATA and no range at all")
    void stale() {
        ForecastResponse r = forecast(Bars.randomWalk(500, 0.01, 1, END), END.plusDays(14), "20D");
        assertThat(r.getStatus()).isEqualTo("STALE_DATA");
        assertThat(r.getScenarios()).isEmpty();
        assertThat(r.getRange90()).isNull();
        assertThat(r.getStatusReason()).contains(END.toString());
    }

    @Test
    @DisplayName("Under 121 valid bars: INSUFFICIENT_DATA, no range")
    void insufficient() {
        ForecastResponse r = forecast(Bars.randomWalk(100, 0.01, 1, END), END, "5D");
        assertThat(r.getStatus()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(r.getScenarios()).isEmpty();
    }

    @Test
    @DisplayName("An unadjusted split inside the volatility window blocks the range")
    void split() {
        List<PriceHistory> bars = Bars.randomWalk(500, 0.01, 1, END);
        for (int i = 450; i < bars.size(); i++) {
            PriceHistory b = bars.get(i);
            BigDecimal f = new BigDecimal("0.5");
            b.setOpen(b.getOpen().multiply(f)); b.setHigh(b.getHigh().multiply(f)); b.setLow(b.getLow().multiply(f)); b.setClose(b.getClose().multiply(f));
        }
        ForecastResponse r = forecast(bars, END, "20D");
        assertThat(r.getStatus()).isEqualTo("DATA_QUALITY_WARNING");
        assertThat(r.getScenarios()).isEmpty();
    }

    @Test
    @DisplayName("Short history: ranges are given but probabilities are 'unavailable', never invented")
    void probabilityUnavailable() {
        ForecastResponse r = forecast(Bars.randomWalk(400, 0.01, 1, END), END, "20D");
        assertThat(r.getStatus()).isEqualTo("OK");
        assertThat(r.getProbabilityStatus()).isEqualTo("UNAVAILABLE");
        assertThat(r.getScenarios()).hasSize(3).allSatisfy(s -> assertThat(s.getProbability()).isNull());
        assertThat(r.getRange90().getLow()).isLessThan(r.getCurrentPrice());
    }

    @Test
    @DisplayName("Long history: probabilities are measured frequencies with intervals, and differ between instruments")
    void probabilityEmpirical() {
        ForecastResponse a = forecast(Bars.randomWalk(2600, 0.01, 1, END), END, "5D");
        ForecastResponse b = forecast(Bars.randomWalk(2600, 0.02, 99, END), END, "5D");
        assertThat(a.getProbabilityStatus()).isEqualTo("EMPIRICAL");
        ForecastResponse.Scenario base = a.getScenarios().get(1);
        assertThat(base.getLabel()).isEqualTo("Base");
        assertThat(base.getProbability()).isCloseTo(50.0, within(5.0));
        assertThat(base.getProbabilityCiLow()).isLessThan(base.getProbability());
        assertThat(base.getProbabilityCiHigh()).isGreaterThan(base.getProbability());
        assertThat(a.getScenarios().get(1).getProbability()).isNotEqualTo(b.getScenarios().get(1).getProbability());
        assertThat(a.getCalibration().getCurve()).hasSize(10);
    }

    @Test
    @DisplayName("The range is consistent with the stated σ: 90% range = P·exp(±1.645·σ·√h)")
    void rangeConsistency() {
        ForecastResponse r = forecast(Bars.randomWalk(600, 0.012, 4, END), END, "20D");
        double p = r.getCurrentPrice(), hs = r.getVolatility().getHorizonPct() / 100;
        assertThat(hs).isCloseTo(r.getVolatility().getDailyPct() / 100 * Math.sqrt(20), within(1e-5));
        assertThat(r.getRange90().getHigh()).isCloseTo(p * Math.exp(1.6448536 * hs), within(0.02));
        assertThat(r.getRange90().getLow()).isCloseTo(p * Math.exp(-1.6448536 * hs), within(0.02));
        assertThat(r.getDirectional().getModelUpProbability()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("Horizons: 1D/5D/20D/60D accepted, legacy 1W/4W/3M mapped, unknown rejected")
    void horizons() {
        assertThat(ForecastService.tradingDays("1D")).isEqualTo(1);
        assertThat(ForecastService.tradingDays("60D")).isEqualTo(60);
        assertThat(ForecastService.tradingDays("1W")).isEqualTo(5);
        assertThat(ForecastService.tradingDays("3M")).isEqualTo(60);
        assertThatThrownBy(() -> ForecastService.tradingDays("7Y")).isInstanceOf(IllegalArgumentException.class);
    }
}
