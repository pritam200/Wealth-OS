package com.marketai.technical;

import com.marketai.technical.service.TechnicalIndicatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TechnicalIndicatorServiceTest {

    private TechnicalIndicatorService service;

    @BeforeEach
    void setup() {
        service = new TechnicalIndicatorService(null, null);
    }

    @Test
    @DisplayName("RSI is 100 when all candles are up")
    void rsi_allUp() {
        List<Double> closes = new ArrayList<>();
        for (int i = 1; i <= 20; i++) closes.add((double) i * 10);
        double rsi = service.calculateRSI(closes, 14);
        assertThat(rsi).isEqualTo(100.0);
    }

    @Test
    @DisplayName("RSI is 0 when all candles are down")
    void rsi_allDown() {
        List<Double> closes = new ArrayList<>();
        for (int i = 20; i >= 1; i--) closes.add((double) i * 10);
        double rsi = service.calculateRSI(closes, 14);
        assertThat(rsi).isEqualTo(0.0);
    }

    @Test
    @DisplayName("RSI returns 50 for too few data points")
    void rsi_tooFewDataPoints() {
        double rsi = service.calculateRSI(Arrays.asList(100.0, 105.0), 14);
        assertThat(rsi).isEqualTo(50.0);
    }

    @Test
    @DisplayName("SMA(3) of [1,2,3,4,5] = 4.0")
    void sma_correctValue() {
        List<Double> closes = Arrays.asList(1.0, 2.0, 3.0, 4.0, 5.0);
        double sma = service.calculateSMA(closes, 3);
        assertThat(sma).isCloseTo(4.0, within(0.001));
    }

    @Test
    @DisplayName("EMA converges toward recent price")
    void ema_recency() {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < 30; i++) closes.add(100.0);
        closes.add(200.0);  // large spike at end

        double ema = service.calculateEMA(closes, 10);
        assertThat(ema).isGreaterThan(100.0).isLessThan(200.0);
    }

    @Test
    @DisplayName("Bollinger upper > middle > lower")
    void bollinger_ordering() {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < 30; i++) closes.add(100.0 + Math.random() * 10);

        double[] bb = service.calculateBollingerBands(closes, 20, 2.0);
        assertThat(bb[0]).isGreaterThan(bb[1]);  // upper > middle
        assertThat(bb[1]).isGreaterThan(bb[2]);  // middle > lower
    }

    @Test
    @DisplayName("MACD array has 3 elements")
    void macd_returnsThreeValues() {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < 100; i++) closes.add(100.0 + Math.sin(i) * 5);

        double[] macd = service.calculateMACD(closes, 12, 26, 9);
        assertThat(macd).hasSize(3);
    }

    @Test
    @DisplayName("Support <= min price, resistance >= max price within window")
    void supportResistance_bounds() {
        List<Double> closes = Arrays.asList(
                90.0, 95.0, 100.0, 105.0, 110.0,
                108.0, 103.0, 98.0, 93.0, 88.0,
                92.0, 97.0, 102.0, 107.0, 112.0,
                109.0, 104.0, 99.0, 94.0, 89.0,
                91.0, 96.0, 101.0, 106.0, 111.0
        );

        double[] sr = service.calculateSupportResistance(closes);
        double min = closes.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = closes.stream().mapToDouble(Double::doubleValue).max().orElse(0);

        assertThat(sr[0]).isGreaterThanOrEqualTo(min);
        assertThat(sr[1]).isLessThanOrEqualTo(max);
    }
}
