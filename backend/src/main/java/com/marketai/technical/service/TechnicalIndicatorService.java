package com.marketai.technical.service;

import com.marketai.market.entity.PriceHistory;
import com.marketai.market.quality.DailySeries;
import com.marketai.market.quality.SeriesStatus;
import com.marketai.market.service.MarketDataService;
import com.marketai.signal.service.MarketStructureAnalyzer;
import com.marketai.technical.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import com.marketai.common.quality.DataQuality;

/**
 * The one place technical indicators, trend and support/resistance are computed. Reads the
 * validated daily series from {@link MarketDataService#getDailySeries}; all formulas live in
 * {@link Indicators}, {@link TrendModel} and {@link SupportResistanceAnalyzer}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TechnicalIndicatorService {

    private final MarketDataService marketDataService;
    private final MarketStructureAnalyzer structureAnalyzer = new MarketStructureAnalyzer();

    /** Below this many valid bars nothing is computed. */
    public static final int MIN_BARS = 20;
    /** Daily returns used for volatility — about six months, recent enough to reflect the current regime. */
    public static final int VOLATILITY_WINDOW = 120;
    public static final String TIMEFRAME = "1D";

    @Cacheable(value = "technicals", key = "T(com.marketai.market.service.MarketDataService).canonicalSymbol(#symbol)")
    public TechnicalAnalysisDto analyse(String symbol) {
        return analyse(marketDataService.getDailySeries(symbol, MIN_BARS));
    }

    public TechnicalAnalysisDto analyse(DailySeries series) {
        List<PriceHistory> bars = series.bars();
        if (series.status() == SeriesStatus.INSUFFICIENT_DATA) {
            log.info("TECHNICALS INSUFFICIENT — {}: {} valid daily bar(s)", series.symbol(), bars.size());
            return base(series)
                    .price(series.last() != null ? series.last().getClose() : null)
                    .trend("INSUFFICIENT_DATA")
                    .dataQuality(DataQuality.INSUFFICIENT.wire())
                    .indicators(List.of())
                    .build();
        }

        List<Double> c = series.closes();
        double price = c.get(c.size() - 1);
        LocalDate asOf = series.lastBarDate();
        int n = c.size();

        Double rsi = Indicators.rsi(c, 14);
        double[] macd = Indicators.macd(c, 12, 26, 9);
        Double sma20 = Indicators.sma(c, 20), sma50 = Indicators.sma(c, 50), sma100 = Indicators.sma(c, 100), sma200 = Indicators.sma(c, 200);
        Double ema20 = n >= Indicators.emaBarsRequired(20) ? Indicators.ema(c, 20) : null;
        Double ema50 = n >= Indicators.emaBarsRequired(50) ? Indicators.ema(c, 50) : null;
        Double ema200 = n >= Indicators.emaBarsRequired(200) ? Indicators.ema(c, 200) : null;
        double[] bb = Indicators.bollinger(c, 20, 2.0);
        Double atr = Indicators.atr(bars, 14);
        double[] adx = Indicators.adx(bars, 14);
        List<Double> rets = Indicators.logReturns(c, VOLATILITY_WINDOW);
        Double dailyVol = rets.size() >= 20 ? Indicators.stdev(rets) : null;
        VolumeProfile volume = volumeProfile(bars);
        SupportResistance levels = SupportResistanceAnalyzer.analyse(bars, atr, sma50, sma200);
        MarketStructureAnalyzer.StructureRead structure = structureAnalyzer.analyse(bars);
        TrendAssessment trend = TrendModel.classify(new TrendModel.Inputs(price, sma50, sma200,
                Indicators.smaAt(c, 50, 20), ema20, ema50,
                structure.getStructure().name(), structure.getDetail(),
                adx != null ? adx[0] : null, adx != null ? adx[1] : null, adx != null ? adx[2] : null,
                rsi, macd != null ? macd[2] : null,
                volume.getRelativeVolume() != null ? volume.getRelativeVolume().doubleValue() : null));

        List<IndicatorValue> ind = new ArrayList<>();
        ind.add(iv("RSI14", "RSI", rsi, "index 0–100", "Wilder: seed = mean gain/loss of first 14 changes; avg = (avg·13 + x)/14; RSI = 100 − 100/(1 + avgGain/avgLoss)", 14, 15, n, asOf));
        ind.add(iv("SMA20", "SMA 20", sma20, "₹", "Mean of the last 20 closes", 20, 20, n, asOf));
        ind.add(iv("SMA50", "SMA 50", sma50, "₹", "Mean of the last 50 closes", 50, 50, n, asOf));
        ind.add(iv("SMA100", "SMA 100", sma100, "₹", "Mean of the last 100 closes", 100, 100, n, asOf));
        ind.add(iv("SMA200", "SMA 200", sma200, "₹", "Mean of the last 200 closes", 200, 200, n, asOf));
        String emaF = "k = 2/(n+1), seeded with the SMA of the first n closes; EMA = close·k + EMA·(1−k); shown once 2n bars exist";
        ind.add(iv("EMA20", "EMA 20", ema20, "₹", emaF, 20, 40, n, asOf));
        ind.add(iv("EMA50", "EMA 50", ema50, "₹", emaF, 50, 100, n, asOf));
        ind.add(iv("EMA200", "EMA 200", ema200, "₹", emaF, 200, 400, n, asOf));
        String macdF = "EMA12 − EMA26; signal = EMA9 of that line; histogram = line − signal";
        ind.add(iv("MACD", "MACD line", macd != null ? macd[0] : null, "₹", macdF, 26, 34, n, asOf));
        ind.add(iv("MACD_SIGNAL", "MACD signal", macd != null ? macd[1] : null, "₹", macdF, 9, 34, n, asOf));
        ind.add(iv("MACD_HIST", "MACD histogram", macd != null ? macd[2] : null, "₹", macdF, 9, 34, n, asOf));
        String bbF = "SMA20 ± 2 × population standard deviation of the same 20 closes";
        ind.add(iv("BB_UPPER", "Bollinger upper", bb != null ? bb[0] : null, "₹", bbF, 20, 20, n, asOf));
        ind.add(iv("BB_LOWER", "Bollinger lower", bb != null ? bb[2] : null, "₹", bbF, 20, 20, n, asOf));
        ind.add(iv("ATR14", "ATR", atr, "₹", "TR = max(H−L, |H−prevC|, |L−prevC|); Wilder: seed = mean of first 14 TR; ATR = (ATR·13 + TR)/14 through the latest bar", 14, 15, n, asOf));
        ind.add(iv("ADX14", "ADX", adx != null ? adx[0] : null, "index 0–100", "Wilder: smoothed +DM/−DM/TR (S − S/14 + x); DI = 100·DM/TR; DX = 100·|+DI − −DI|/(+DI + −DI); ADX = Wilder average of DX", 14, 29, n, asOf));
        ind.add(iv("VOL_DAILY", "Daily volatility", dailyVol != null ? dailyVol * 100 : null, "% per day", "Sample standard deviation of daily log returns ln(Ct/Ct−1), last 120 sessions", VOLATILITY_WINDOW, 21, n, asOf));
        ind.add(iv("VOL_ANNUAL", "Annualised volatility", dailyVol != null ? dailyVol * Math.sqrt(252) * 100 : null, "% per year", "Daily volatility × √252", VOLATILITY_WINDOW, 21, n, asOf));

        PriceLevel ns = levels.getNearestSupport(), nr = levels.getNearestResistance();
        List<PriceHistory> year = bars.subList(Math.max(0, n - 250), n);
        double hi52 = year.stream().mapToDouble(b -> b.getHigh().doubleValue()).max().orElseThrow();
        double lo52 = year.stream().mapToDouble(b -> b.getLow().doubleValue()).min().orElseThrow();
        return base(series)
                .price(round(price))
                .rsi(round(rsi))
                .macd(macd != null ? round(macd[0]) : null)
                .macdSignal(macd != null ? round(macd[1]) : null)
                .macdHistogram(macd != null ? round(macd[2]) : null)
                .sma20(round(sma20)).sma50(round(sma50)).sma100(round(sma100)).sma200(round(sma200))
                .ema20(round(ema20)).ema50(round(ema50)).ema200(round(ema200))
                .bollingerUpper(bb != null ? round(bb[0]) : null)
                .bollingerMiddle(bb != null ? round(bb[1]) : null)
                .bollingerLower(bb != null ? round(bb[2]) : null)
                .atr(round(atr))
                .atrPct(atr != null ? round(atr / price * 100) : null)
                .adx(adx != null ? round(adx[0]) : null)
                .plusDi(adx != null ? round(adx[1]) : null)
                .minusDi(adx != null ? round(adx[2]) : null)
                .dailyVolatilityPct(dailyVol != null ? BigDecimal.valueOf(dailyVol * 100).setScale(3, RoundingMode.HALF_UP) : null)
                .annualizedVolatilityPct(dailyVol != null ? round(dailyVol * Math.sqrt(252) * 100) : null)
                .volatilityBars(dailyVol != null ? rets.size() : null)
                .volume(volume)
                .high52w(round(hi52)).low52w(round(lo52)).range52wSessions(year.size())
                .rangePosition52wPct(hi52 > lo52 ? round((price - lo52) / (hi52 - lo52) * 100) : null)
                .support(ns != null ? ns.getPrice() : null)
                .resistance(nr != null ? nr.getPrice() : null)
                .levels(levels)
                .trend(trend.getLabel())
                .trendAssessment(trend)
                .indicators(ind)
                .dataQuality((sma200 != null ? DataQuality.FULL : DataQuality.PARTIAL).wire())
                .build();
    }

    private TechnicalAnalysisDto.TechnicalAnalysisDtoBuilder base(DailySeries s) {
        return TechnicalAnalysisDto.builder()
                .symbol(s.symbol())
                .seriesStatus(s.status().name())
                .dataIssues(s.issues())
                .barsAvailable(s.size())
                .barsRejected(s.rejected())
                .firstBarDate(s.isEmpty() ? null : s.bars().get(0).getDate())
                .lastBarDate(s.lastBarDate())
                .expectedSession(s.expectedSession())
                .stale(s.stale())
                .source(s.provider() != null ? s.provider() : "stored history")
                .dataUpdatedAt(s.lastUpdated())
                .timeframe(TIMEFRAME);
    }

    /**
     * Volume over bars that report it. Latest vs the 20 sessions before it; trend = 20- vs
     * 50-session average (RISING above 1.1, FALLING below 0.9 — descriptive cut-offs, not
     * signals); unusual = more than 2 standard deviations above the 20-session mean.
     */
    static VolumeProfile volumeProfile(List<PriceHistory> bars) {
        List<Long> v = new ArrayList<>();
        for (PriceHistory b : bars) v.add(b.getVolume() != null && b.getVolume() > 0 ? b.getVolume() : null);
        Long latest = v.isEmpty() ? null : v.get(v.size() - 1);
        List<Double> prior20 = positives(v, v.size() - 21, v.size() - 1);
        List<Double> last50 = positives(v, v.size() - 50, v.size());
        List<Double> last20 = positives(v, v.size() - 20, v.size());
        int withVol = (int) v.stream().filter(java.util.Objects::nonNull).count();
        if (latest == null || prior20.size() < 15) {
            return VolumeProfile.builder().latestVolume(latest).barsWithVolume(withVol)
                    .reason(latest == null ? "Latest bar has no volume (the feed publishes none for this instrument or day)."
                            : "Fewer than 15 of the previous 20 sessions report volume.").build();
        }
        double avg20 = prior20.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        Double sd = Indicators.stdev(prior20);
        double rel = latest / avg20;
        Double z = sd != null && sd > 0 ? (latest - avg20) / sd : null;
        String trend = null;
        Double ratio = null;
        if (last50.size() >= 40 && last20.size() >= 15) {
            ratio = last20.stream().mapToDouble(Double::doubleValue).average().orElseThrow()
                    / last50.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
            trend = ratio > 1.1 ? "RISING" : ratio < 0.9 ? "FALLING" : "FLAT";
        }
        return VolumeProfile.builder()
                .latestVolume(latest)
                .averageVolume20(BigDecimal.valueOf(avg20).setScale(0, RoundingMode.HALF_UP))
                .relativeVolume(BigDecimal.valueOf(rel).setScale(2, RoundingMode.HALF_UP))
                .volumeTrend(trend)
                .volumeTrendRatio(ratio != null ? BigDecimal.valueOf(ratio).setScale(2, RoundingMode.HALF_UP) : null)
                .unusual(z != null ? z > 2 : null)
                .zScore(z != null ? BigDecimal.valueOf(z).setScale(2, RoundingMode.HALF_UP) : null)
                .barsWithVolume(withVol)
                .build();
    }

    private static List<Double> positives(List<Long> v, int from, int to) {
        List<Double> out = new ArrayList<>();
        for (int i = Math.max(0, from); i < to; i++) if (v.get(i) != null) out.add(v.get(i).doubleValue());
        return out;
    }

    private static IndicatorValue iv(String key, String name, Double value, String unit, String formula,
                                     int period, int required, int available, LocalDate asOf) {
        boolean ok = value != null && available >= required;
        return IndicatorValue.builder().key(key).name(name)
                .value(ok ? BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP) : null)
                .unit(unit).formula(formula).period(period).timeframe(TIMEFRAME).asOf(asOf)
                .barsRequired(required).barsAvailable(available).available(ok)
                .reason(ok ? null : "Needs " + required + " daily bars; " + available + " available.")
                .build();
    }

    private static BigDecimal round(Double v) {
        return v == null ? null : BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }
}
