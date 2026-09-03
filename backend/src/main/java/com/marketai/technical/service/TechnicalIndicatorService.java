package com.marketai.technical.service;

import com.marketai.market.entity.PriceHistory;
import com.marketai.market.repository.PriceHistoryRepository;
import com.marketai.market.service.MarketDataService;
import com.marketai.technical.dto.TechnicalAnalysisDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TechnicalIndicatorService {

    private final PriceHistoryRepository priceHistoryRepository;
    private final MarketDataService marketDataService;

    private static final MathContext MC = new MathContext(10, RoundingMode.HALF_UP);

    // Imported but never actually applied — every analyse() call recomputed indicators from
    // scratch on every request. This is the underlying feed for RecommendationEngine, so
    // caching it also caps repeated Yahoo history fetches across Portfolio/Advisor/Research.
    @Cacheable(value = "technicals", key = "#symbol")
    public TechnicalAnalysisDto analyse(String symbol) {
        List<PriceHistory> history = priceHistoryRepository.findTop200BySymbolOrderByDateDesc(symbol);

        if (history.size() < 20) {
            try {
                marketDataService.fetchAndStorePriceHistory(symbol, "1y");
                history = priceHistoryRepository.findTop200BySymbolOrderByDateDesc(symbol);
            } catch (Exception e) {
                log.warn("Could not fetch price history for {}: {}", symbol, e.getMessage());
            }
        }

        // Not enough real history to compute any indicator. Report that honestly instead of
        // synthesising indicator-shaped numbers from a single day's price change.
        if (history.size() < 5) {
            return insufficientData(symbol, history.size());
        }

        // Reverse to chronological order
        List<Double> closes = history.stream()
                .sorted((a, b) -> a.getDate().compareTo(b.getDate()))
                .map(h -> h.getClose().doubleValue())
                .collect(Collectors.toList());

        double currentPrice = closes.isEmpty() ? 0 : closes.get(closes.size() - 1);

        double rsi14 = calculateRSI(closes, 14);
        double[] macd = calculateMACD(closes, 12, 26, 9);
        double sma20 = calculateSMA(closes, 20);
        double sma50 = calculateSMA(closes, 50);
        double sma200 = calculateSMA(closes, 200);
        double ema20 = calculateEMA(closes, 20);
        double[] bb = calculateBollingerBands(closes, 20, 2.0);
        double atr14 = calculateATR(history, 14);
        double[] sr = calculateSupportResistance(closes);

        // calculateSMA returns 0 (not an error) when there are fewer bars than the period.
        // Treating that 0 as a real average made `price > sma200` trivially true for every
        // symbol with <200 bars, which silently biased their trend toward UPTREND. Carry the
        // "not computable" state through as null instead.
        Double sma200OrNull = sma200 > 0 ? sma200 : null;

        String trend = determineTrend(currentPrice, sma20, sma50, sma200OrNull);
        String signal = generateSignal(rsi14, macd[0], macd[1], currentPrice, sma20, sma50);

        return TechnicalAnalysisDto.builder()
                .symbol(symbol)
                .price(round(currentPrice))
                .rsi(round(rsi14))
                .macd(round(macd[0]))
                .macdSignal(round(macd[1]))
                .macdHistogram(round(macd[2]))
                .sma20(roundOrNull(sma20))
                .sma50(roundOrNull(sma50))
                .sma200(sma200OrNull != null ? round(sma200OrNull) : null)
                .ema20(roundOrNull(ema20))
                .bollingerUpper(roundOrNull(bb[0]))
                .bollingerMiddle(roundOrNull(bb[1]))
                .bollingerLower(roundOrNull(bb[2]))
                .atr(roundOrNull(atr14))
                .support(roundOrNull(sr[0]))
                .resistance(roundOrNull(sr[1]))
                .trend(trend)
                .signal(signal)
                .signalStrength(calculateSignalStrength(rsi14, macd, currentPrice, sma20))
                .dataQuality(sma200OrNull != null ? "FULL" : "PARTIAL")
                .barsAvailable(history.size())
                .build();
    }

    // ─── RSI ─────────────────────────────────────────────────────────────────

    public double calculateRSI(List<Double> closes, int period) {
        if (closes.size() < period + 1) return 50.0;

        double avgGain = 0, avgLoss = 0;

        for (int i = 1; i <= period; i++) {
            double change = closes.get(i) - closes.get(i - 1);
            if (change > 0) avgGain += change;
            else avgLoss += Math.abs(change);
        }
        avgGain /= period;
        avgLoss /= period;

        for (int i = period + 1; i < closes.size(); i++) {
            double change = closes.get(i) - closes.get(i - 1);
            double gain = Math.max(change, 0);
            double loss = Math.max(-change, 0);
            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;
        }

        if (avgLoss == 0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1 + rs));
    }

    // ─── MACD ────────────────────────────────────────────────────────────────

    public double[] calculateMACD(List<Double> closes, int fast, int slow, int signal) {
        List<Double> emaFast = computeEMAList(closes, fast);
        List<Double> emaSlow = computeEMAList(closes, slow);

        int diff = emaSlow.size();
        List<Double> macdLine = new ArrayList<>();
        for (int i = 0; i < diff; i++) {
            macdLine.add(emaFast.get(emaFast.size() - diff + i) - emaSlow.get(i));
        }

        List<Double> signalLine = computeEMAList(macdLine, signal);
        double macdVal = macdLine.isEmpty() ? 0 : macdLine.get(macdLine.size() - 1);
        double signalVal = signalLine.isEmpty() ? 0 : signalLine.get(signalLine.size() - 1);

        return new double[]{macdVal, signalVal, macdVal - signalVal};
    }

    // ─── SMA ─────────────────────────────────────────────────────────────────

    public double calculateSMA(List<Double> closes, int period) {
        if (closes.size() < period) return 0;
        List<Double> slice = closes.subList(closes.size() - period, closes.size());
        return slice.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    // ─── EMA ─────────────────────────────────────────────────────────────────

    public double calculateEMA(List<Double> closes, int period) {
        List<Double> emas = computeEMAList(closes, period);
        return emas.isEmpty() ? 0 : emas.get(emas.size() - 1);
    }

    private List<Double> computeEMAList(List<Double> closes, int period) {
        List<Double> emas = new ArrayList<>();
        if (closes.size() < period) return emas;

        double k = 2.0 / (period + 1);
        double ema = closes.subList(0, period).stream()
                .mapToDouble(Double::doubleValue).average().orElse(0);
        emas.add(ema);

        for (int i = period; i < closes.size(); i++) {
            ema = closes.get(i) * k + ema * (1 - k);
            emas.add(ema);
        }
        return emas;
    }

    // ─── Bollinger Bands ─────────────────────────────────────────────────────

    public double[] calculateBollingerBands(List<Double> closes, int period, double stdDevMultiplier) {
        if (closes.size() < period) return new double[]{0, 0, 0};

        double sma = calculateSMA(closes, period);
        List<Double> slice = closes.subList(closes.size() - period, closes.size());
        double variance = slice.stream()
                .mapToDouble(c -> Math.pow(c - sma, 2))
                .average().orElse(0);
        double stdDev = Math.sqrt(variance);

        return new double[]{
                sma + stdDevMultiplier * stdDev,
                sma,
                sma - stdDevMultiplier * stdDev
        };
    }

    // ─── ATR ─────────────────────────────────────────────────────────────────

    public double calculateATR(List<PriceHistory> history, int period) {
        if (history.size() < 2) return 0;
        List<PriceHistory> sorted = history.stream()
                .sorted((a, b) -> a.getDate().compareTo(b.getDate()))
                .collect(Collectors.toList());

        double atr = 0;
        int count = 0;
        for (int i = 1; i < sorted.size() && count < period; i++, count++) {
            double high = sorted.get(i).getHigh().doubleValue();
            double low = sorted.get(i).getLow().doubleValue();
            double prevClose = sorted.get(i - 1).getClose().doubleValue();
            double tr = Math.max(high - low, Math.max(
                    Math.abs(high - prevClose), Math.abs(low - prevClose)));
            atr += tr;
        }
        return count > 0 ? atr / count : 0;
    }

    // ─── Support & Resistance ────────────────────────────────────────────────

    public double[] calculateSupportResistance(List<Double> closes) {
        if (closes.size() < 20) return new double[]{0, 0};

        List<Double> recent = closes.subList(Math.max(0, closes.size() - 50), closes.size());
        // A bad/missing price-history row (close = 0) must not win the min() and be
        // reported as "support" — filter to valid positive closes first.
        double support = recent.stream().mapToDouble(Double::doubleValue).filter(c -> c > 0).min().orElse(0);
        double resistance = recent.stream().mapToDouble(Double::doubleValue).filter(c -> c > 0).max().orElse(0);
        return new double[]{support, resistance};
    }

    // ─── Trend ───────────────────────────────────────────────────────────────

    /**
     * @param sma200 null when fewer than 200 bars are stored — the long-term filter is then
     *               genuinely unknown and must not be counted as either above or below.
     *               (Passing 0 here previously made every short-history symbol read as
     *               "above its 200-DMA".)
     */
    private String determineTrend(double price, double sma20, double sma50, Double sma200) {
        if (price == 0) return "UNKNOWN";
        // A period whose SMA could not be computed yields 0 from calculateSMA; treat that as
        // unknown rather than as a level the price is above.
        boolean sma20Known = sma20 > 0, sma50Known = sma50 > 0, sma200Known = sma200 != null;
        boolean aboveSma20  = sma20Known  && price > sma20;
        boolean aboveSma50  = sma50Known  && price > sma50;
        boolean aboveSma200 = sma200Known && price > sma200;
        boolean belowSma20  = sma20Known  && price < sma20;
        boolean belowSma50  = sma50Known  && price < sma50;
        boolean belowSma200 = sma200Known && price < sma200;
        boolean sma20AboveSma50 = sma20Known && sma50Known && sma20 > sma50;

        if (aboveSma20 && aboveSma50 && aboveSma200 && sma20AboveSma50) return "STRONG_UPTREND";
        if (aboveSma50 && aboveSma200) return "UPTREND";
        // Strictest case first — previously the looser "all three below" test was checked
        // before this one and shadowed it, making STRONG_DOWNTREND reachable only in the odd
        // case of price above SMA20 but below SMA50/200. Since STRONG_DOWNTREND is the
        // recommendation engine's only unconditional EXIT trigger, EXIT effectively never fired.
        if (belowSma20 && belowSma50 && belowSma200) return "STRONG_DOWNTREND";
        if (belowSma50 && belowSma200) return "DOWNTREND";
        return "SIDEWAYS";
    }

    // ─── Signal ──────────────────────────────────────────────────────────────

    private String generateSignal(double rsi, double macd, double macdSignal,
                                   double price, double sma20, double sma50) {
        int bullish = 0, bearish = 0;

        if (rsi < 30) bullish += 2;
        else if (rsi < 40) bullish += 1;
        else if (rsi > 70) bearish += 2;
        else if (rsi > 60) bearish += 1;

        if (macd > macdSignal) bullish++;
        else bearish++;

        if (price > sma20) bullish++;
        else bearish++;

        if (price > sma50) bullish++;
        else bearish++;

        if (bullish >= 3 && bearish <= 1) return "BUY";
        if (bearish >= 3 && bullish <= 1) return "SELL";
        return "HOLD";
    }

    private String calculateSignalStrength(double rsi, double[] macd,
                                            double price, double sma20) {
        double score = 0;
        if (rsi < 30 || rsi > 70) score += 2;
        else if (rsi < 40 || rsi > 60) score += 1;
        if (Math.abs(macd[0] - macd[1]) > Math.abs(price * 0.001)) score += 1;
        if (Math.abs(price - sma20) / sma20 > 0.03) score += 1;

        if (score >= 3) return "STRONG";
        if (score >= 2) return "MODERATE";
        return "WEAK";
    }

    /**
     * Too little stored history to compute anything. Returns only the live price (which is
     * real) and marks the whole payload INSUFFICIENT so callers can say "insufficient data"
     * rather than presenting derived-from-nothing numbers as analysis.
     *
     * This replaces a previous fallback that invented an RSI from the day's change %, set
     * every moving average equal to the current price, zeroed MACD, and derived ATR as a flat
     * 1% of price — all returned in a DTO with no marker distinguishing it from real output.
     */
    private TechnicalAnalysisDto insufficientData(String symbol, int bars) {
        BigDecimal price = null;
        try {
            com.marketai.market.dto.QuoteDto q = marketDataService.getQuote(symbol);
            if (q != null && q.getCurrentPrice() != null) price = q.getCurrentPrice();
        } catch (Exception e) {
            log.warn("No quote available for {} either: {}", symbol, e.getMessage());
        }
        log.info("TECHNICALS INSUFFICIENT — {}: only {} daily bar(s) stored; returning no indicators", symbol, bars);
        return TechnicalAnalysisDto.builder()
                .symbol(symbol)
                .price(price)
                .trend("UNKNOWN")
                .signal("INSUFFICIENT_DATA")
                .signalStrength(null)
                .dataQuality("INSUFFICIENT")
                .barsAvailable(bars)
                .build();
    }

    private BigDecimal round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    /** null when the indicator wasn't computable (calculateSMA/ATR/etc. return 0 in that case). */
    private BigDecimal roundOrNull(double value) {
        return value > 0 ? round(value) : null;
    }
}
