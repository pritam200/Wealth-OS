package com.marketai.forecast.service;

import com.marketai.forecast.dto.ForecastResponse;
import com.marketai.forecast.dto.ForecastResponse.Scenario;
import com.marketai.technical.dto.TechnicalAnalysisDto;
import com.marketai.technical.service.TechnicalIndicatorService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Reference-based forecast. Rather than fixed guessed percentages, the expected
 * move is derived from the instrument's own realised volatility (14-day ATR) scaled
 * to the horizon by the square-root-of-time rule — the standard way to project a
 * volatility band. Direction probabilities are set by the actual trend (SMA
 * structure) and momentum (RSI). This keeps predictions grounded in real history.
 */
@Service
@RequiredArgsConstructor
public class ForecastService {

    private final TechnicalIndicatorService technical;

    private int tradingDays(String horizon) {
        switch (horizon == null ? "" : horizon.toUpperCase()) {
            case "1W": return 5;
            case "2W": return 10;
            case "4W": return 20;
            case "3M": return 63;
            default:   return 10;
        }
    }

    public ForecastResponse forecast(String symbol, String horizon, String displayName) {
        TechnicalAnalysisDto ta = technical.analyse(symbol);
        double price = ta.getPrice() != null ? ta.getPrice().doubleValue() : 0;

        // A forecast is entirely a function of ATR (volatility) and trend. With no stored
        // history there is neither, and the previous `price * 0.015` ATR fallback silently
        // manufactured a volatility band that looked identical to a measured one. Return an
        // explicit no-forecast instead.
        if ("INSUFFICIENT".equals(ta.getDataQuality()) || ta.getAtr() == null) {
            int bars = ta.getBarsAvailable() != null ? ta.getBarsAvailable() : 0;
            return ForecastResponse.builder()
                .symbol(symbol)
                .displayName(displayName != null ? displayName : symbol)
                .horizon(horizon)
                .currentPrice(round(price))
                .scenarios(new ArrayList<>())
                .trend("UNKNOWN")
                .signal("INSUFFICIENT_DATA")
                .dataPoints(bars)
                .basis(String.format(
                    "Insufficient data — only %d day(s) of price history stored for %s. Volatility (ATR) cannot be measured, so no projection range is shown. Fetch price history for this symbol and re-run.",
                    bars, symbol))
                .build();
        }

        double atr   = ta.getAtr().doubleValue();
        double rsi   = ta.getRsi() != null ? ta.getRsi().doubleValue() : 50;
        String trend = ta.getTrend() != null ? ta.getTrend() : "SIDEWAYS";

        int days = tradingDays(horizon);
        double sigma = atr * Math.sqrt(days);           // 1σ expected move over horizon
        if (sigma <= 0) sigma = price * 0.03;

        // Direction probabilities from trend, then adjusted by RSI extremes.
        int bull, base, bear;
        switch (trend) {
            case "STRONG_UPTREND":   bull = 45; base = 40; bear = 15; break;
            case "UPTREND":          bull = 40; base = 40; bear = 20; break;
            case "DOWNTREND":        bull = 20; base = 40; bear = 40; break;
            case "STRONG_DOWNTREND": bull = 15; base = 40; bear = 45; break;
            default:                 bull = 30; base = 45; bear = 25; break; // SIDEWAYS
        }
        if (rsi >= 70)      { int shift = Math.min(10, bull); bull -= shift; bear += shift; } // overbought
        else if (rsi <= 30) { int shift = Math.min(10, bear); bear -= shift; bull += shift; } // oversold

        List<Scenario> scenarios = new ArrayList<>();
        scenarios.add(band("Bull", "up",   bull, price, +0.5 * sigma, +1.5 * sigma, price));
        scenarios.add(band("Base", "flat", base, price, -0.5 * sigma, +0.5 * sigma, price));
        scenarios.add(band("Bear", "down", bear, price, -1.5 * sigma, -0.5 * sigma, price));

        double movePct = price > 0 ? sigma / price * 100 : 0;
        // Report the real stored-bar count rather than asserting a fixed 200/60.
        int bars = ta.getBarsAvailable() != null ? ta.getBarsAvailable() : 0;
        String basis = String.format(
            "Built from %d days of price history%s. 14-day ATR = %.2f (daily volatility); expected 1σ move over %s ≈ ±%.2f (±%.1f%%), scaled by √%d. Trend %s, RSI %.0f set the direction odds.",
            bars,
            "PARTIAL".equals(ta.getDataQuality()) ? " (under 200 — no 200-DMA trend confirmation)" : "",
            atr, horizon, sigma, movePct, days,
            trend.toLowerCase().replace('_', ' '), rsi);

        return ForecastResponse.builder()
            .symbol(symbol)
            .displayName(displayName != null ? displayName : symbol)
            .horizon(horizon)
            .currentPrice(round(price))
            .scenarios(scenarios)
            .atr(round(atr))
            .expectedMove(round(sigma))
            .rsi(round(rsi))
            .sma50(ta.getSma50() != null ? ta.getSma50().doubleValue() : 0)
            .sma200(ta.getSma200() != null ? ta.getSma200().doubleValue() : 0)
            .support(ta.getSupport() != null ? ta.getSupport().doubleValue() : 0)
            .resistance(ta.getResistance() != null ? ta.getResistance().doubleValue() : 0)
            .trend(trend)
            .signal(ta.getSignal() != null ? ta.getSignal() : "HOLD")
            .dataPoints(bars)
            .basis(basis)
            .build();
    }

    private Scenario band(String label, String dir, int prob, double price, double lo, double hi, double ref) {
        double low = price + lo, high = price + hi;
        return Scenario.builder()
            .label(label).direction(dir).probability(prob)
            .low(round(low)).high(round(high))
            .movePctLow(round(ref > 0 ? (low / ref - 1) * 100 : 0))
            .movePctHigh(round(ref > 0 ? (high / ref - 1) * 100 : 0))
            .build();
    }

    private double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
