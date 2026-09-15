package com.marketai.signal.service;

import com.marketai.market.entity.PriceHistory;
import com.marketai.market.repository.PriceHistoryRepository;
import com.marketai.signal.dto.SignalPayload;
import com.marketai.technical.service.TechnicalIndicatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import com.marketai.common.quality.DataQuality;

/**
 * Multi-factor signal engine.
 *
 * Scores by FAMILY, not by indicator, and that is the central design decision. The common
 * approach — averaging a pile of oscillators, the way public technical-rating models do —
 * looks like broad confluence but isn't: RSI, CCI, Stochastic, ROC and the MACD line have
 * measured pairwise correlation above 0.9, so eleven oscillators behave like two or three
 * independent opinions. Equal-weighting them silently over-weights momentum roughly four to
 * one. Here each family votes once, so adding another oscillator cannot move the score.
 *
 * Every factor carries its own availability. Missing inputs are null with a reason and lower
 * the attainable ceiling, rather than scoring 0 and quietly dragging the composite to HOLD.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SignalEngine {

    public static final String VERSION = "signal-engine/2.0.0";

    private final PriceHistoryRepository priceHistoryRepository;
    private final TechnicalIndicatorService indicators;
    private final MarketStructureAnalyzer structureAnalyzer;

    /* Family weights. Deliberately flat-ish across independent families rather than
       concentrated in momentum, which is where correlated indicators would pile up. */
    private static final double W_TREND      = 0.25;
    private static final double W_MOMENTUM   = 0.25;
    private static final double W_STRUCTURE  = 0.25;
    private static final double W_VOLUME     = 0.15;
    private static final double W_VOLATILITY = 0.10;

    private static final BigDecimal ATR_STOP_MULTIPLE   = new BigDecimal("2.0");
    private static final BigDecimal ATR_TARGET_MULTIPLE = new BigDecimal("4.0");  // 2R

    /** Below this fraction of average volume a breakout is treated as a thin-liquidity trap. */
    private static final double LOW_VOLUME_RATIO = 0.7;

    private static final int BUY_THRESHOLD  = 20;
    private static final int SELL_THRESHOLD = -20;

    public SignalPayload analyse(String symbol) {
        List<PriceHistory> daily = ascending(priceHistoryRepository.findTop200BySymbolOrderByDateDesc(symbol));

        List<String> used = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<SignalPayload.Guardrail> guardrails = new ArrayList<>();

        if (daily.size() < Timeframe.D1.minBarsForSignal()) {
            guardrails.add(SignalPayload.Guardrail.builder()
                .rule("INSUFFICIENT_BARS").action("SUPPRESSED")
                .detail(daily.size() + " daily bars available; " + Timeframe.D1.minBarsForSignal() + " required.")
                .build());
            return insufficient(symbol, daily, guardrails,
                "Not enough price history to evaluate this symbol.");
        }
        used.add(Timeframe.D1.code());

        // Intraday is opportunistic: these rows only exist once the intraday backfill has run,
        // and their absence weakens the read rather than invalidating it.
        for (Timeframe tf : new Timeframe[]{Timeframe.H1, Timeframe.M15}) {
            List<PriceHistory> bars = ascending(
                priceHistoryRepository.findBySymbolAndIntervalOrderByBarStartDesc(symbol, tf.code()));
            if (bars.size() >= tf.minBarsForSignal()) used.add(tf.code());
            else missing.add(tf.code() + ": " + bars.size() + " bars, need " + tf.minBarsForSignal());
        }

        Map<String, SignalPayload.Factor> factors = new LinkedHashMap<>();
        List<String> rationale = new ArrayList<>();

        List<Double> closes = closes(daily);
        BigDecimal lastClose = daily.get(daily.size() - 1).getClose();

        /* ── Trend ── */
        double sma20 = indicators.calculateSMA(closes, 20);
        double sma50 = indicators.calculateSMA(closes, 50);
        Integer trendScore = null;
        String trendDetail;
        if (sma20 > 0 && sma50 > 0 && lastClose != null) {
            double price = lastClose.doubleValue();
            int s = 0;
            if (price > sma20) s += 30;  else s -= 30;
            if (sma20 > sma50) s += 40;  else s -= 40;
            trendScore = clamp(s);
            trendDetail = String.format("Price %.2f vs SMA20 %.2f and SMA50 %.2f.", price, sma20, sma50);
            rationale.add("Daily trend: " + trendDetail + " (" + signed(trendScore) + ")");
        } else {
            trendDetail = null;
        }
        factors.put("trend", factor(trendScore, W_TREND, Timeframe.D1.code(), trendDetail,
            trendScore == null ? "Moving averages could not be computed." : null));

        /* ── Momentum — ONE vote for the whole family ── */
        double rsi = indicators.calculateRSI(closes, 14);
        double[] macd = indicators.calculateMACD(closes, 12, 26, 9);
        Integer momentumScore = null;
        String momentumDetail = null;
        if (rsi > 0) {
            int s = 0;
            if (rsi > 70)      s -= 30;         // overbought
            else if (rsi < 30) s += 30;         // oversold
            else if (rsi > 55) s += 15;
            else if (rsi < 45) s -= 15;
            // MACD histogram is the one momentum input weakly correlated with the rest
            // (it's a second derivative), so it earns a separate contribution.
            if (macd[2] > 0) s += 25; else s -= 25;
            momentumScore = clamp(s);
            momentumDetail = String.format("RSI(14) %.1f, MACD histogram %.3f.", rsi, macd[2]);
            rationale.add("Momentum: " + momentumDetail + " (" + signed(momentumScore) + ")");
        }
        factors.put("momentum", factor(momentumScore, W_MOMENTUM, Timeframe.D1.code(), momentumDetail,
            momentumScore == null ? "RSI could not be computed." : null));

        /* ── Market structure ── */
        MarketStructureAnalyzer.StructureRead structure = structureAnalyzer.analyse(daily);
        factors.put("marketStructure", factor(structure.getScore(), W_STRUCTURE, Timeframe.D1.code(),
            structure.getDetail(),
            structure.getScore() == null ? "Not enough swing points to read structure." : null));
        if (structure.getScore() != null) {
            rationale.add("Structure: " + structure.getDetail() + " (" + signed(structure.getScore()) + ")");
        }

        /* ── Volume ── */
        Integer volumeScore = null;
        String volumeDetail = null;
        Double volumeRatio = volumeRatio(daily);
        if (volumeRatio != null) {
            volumeScore = clamp((int) Math.round((volumeRatio - 1.0) * 60));
            volumeDetail = String.format("Latest volume %.2fx its 20-bar average.", volumeRatio);
            rationale.add("Volume: " + volumeDetail + " (" + signed(volumeScore) + ")");
        }
        factors.put("volume", factor(volumeScore, W_VOLUME, Timeframe.D1.code(), volumeDetail,
            volumeScore == null ? "Volume history unavailable." : null));

        /* ── Volatility ── */
        double atr = indicators.calculateATR(daily, 14);
        Integer volatilityScore = null;
        String volatilityDetail = null;
        if (atr > 0 && lastClose != null && lastClose.doubleValue() > 0) {
            double atrPct = atr / lastClose.doubleValue() * 100;
            // High volatility is not directional — it reduces conviction either way.
            volatilityScore = atrPct > 5 ? -25 : atrPct > 3 ? -10 : 10;
            volatilityDetail = String.format("ATR(14) is %.2f%% of price.", atrPct);
            rationale.add("Volatility: " + volatilityDetail + " (" + signed(volatilityScore) + ")");
        }
        factors.put("volatility", factor(volatilityScore, W_VOLATILITY, Timeframe.D1.code(), volatilityDetail,
            volatilityScore == null ? "ATR could not be computed." : null));

        /* ── Microstructure — structurally unavailable ── */
        factors.put("microstructure", SignalPayload.Factor.builder()
            .score(null).weight(0).timeframe(null)
            .reason("No order book data source. Requires a broker L2 feed; Yahoo does not publish depth.")
            .build());
        rationale.add("Order book imbalance unavailable (no L2 data source).");

        /* ── Composite over available families only ── */
        double weighted = 0, weightAvailable = 0;
        for (SignalPayload.Factor f : factors.values()) {
            if (f.getScore() == null || f.getWeight() <= 0) continue;
            weighted += f.getScore() * f.getWeight();
            weightAvailable += f.getWeight();
        }
        if (weightAvailable <= 0) {
            return insufficient(symbol, daily, guardrails, "No factor could be computed.");
        }
        int composite = clamp((int) Math.round(weighted / weightAvailable));

        double totalWeight = W_TREND + W_MOMENTUM + W_STRUCTURE + W_VOLUME + W_VOLATILITY;
        int ceiling = (int) Math.round(weightAvailable / totalWeight * 100);

        SignalPayload.Type type = composite >= BUY_THRESHOLD ? SignalPayload.Type.BUY
                                : composite <= SELL_THRESHOLD ? SignalPayload.Type.SELL
                                : SignalPayload.Type.HOLD;

        /* ── Guardrails ── */
        if (type == SignalPayload.Type.BUY && volumeRatio != null && volumeRatio < LOW_VOLUME_RATIO) {
            type = SignalPayload.Type.HOLD;
            guardrails.add(SignalPayload.Guardrail.builder()
                .rule("LOW_VOLUME_TRAP").action("SUPPRESSED")
                .detail(String.format("Volume is %.2fx average — too thin to trust a breakout.", volumeRatio))
                .build());
            rationale.add("Downgraded to HOLD: breakout is not backed by volume.");
        }
        if (type == SignalPayload.Type.BUY
                && structure.getStructure() == MarketStructureAnalyzer.Structure.DOWNTREND) {
            type = SignalPayload.Type.HOLD;
            guardrails.add(SignalPayload.Guardrail.builder()
                .rule("COUNTER_TREND").action("SUPPRESSED")
                .detail("Buy signal contradicts a confirmed downtrend in market structure.")
                .build());
            rationale.add("Downgraded to HOLD: signal runs against prevailing structure.");
        }
        LocalDateTime asOf = barTime(daily.get(daily.size() - 1));
        if (asOf != null && asOf.isBefore(LocalDateTime.now().minusDays(5))) {
            guardrails.add(SignalPayload.Guardrail.builder()
                .rule("STALE_DATA").action("FLAGGED")
                .detail("Newest bar is from " + asOf.toLocalDate() + ".")
                .build());
        }

        int confidence = Math.min(Math.abs(composite), ceiling);

        return SignalPayload.builder()
            .symbol(symbol)
            .asOf(asOf)
            .priceAtSignal(lastClose)
            .signal(type)
            .confidence(confidence)
            .confidenceCeiling(ceiling)
            .dataQuality((missing.isEmpty() ? DataQuality.FULL : DataQuality.PARTIAL).wire())
            .timeframesUsed(used)
            .timeframesMissing(missing)
            .factors(factors)
            .execution(type == SignalPayload.Type.HOLD ? null
                : buildExecution(type, lastClose, atr))
            .guardrails(guardrails)
            .rationale(rationale)
            .engineVersion(VERSION)
            .build();
    }

    /**
     * ATR-derived entry, stop and target.
     *
     * 2x ATR for the stop is the standard convention — wide enough to sit outside ordinary
     * noise. The target is set at twice the risk distance, so R:R is 2:1 by construction
     * rather than by hopeful choice of a round number.
     */
    private SignalPayload.Execution buildExecution(SignalPayload.Type type, BigDecimal price, double atr) {
        if (price == null || atr <= 0) return null;
        BigDecimal atrBd = BigDecimal.valueOf(atr).setScale(2, RoundingMode.HALF_UP);
        BigDecimal stopDist   = atrBd.multiply(ATR_STOP_MULTIPLE);
        BigDecimal targetDist = atrBd.multiply(ATR_TARGET_MULTIPLE);

        boolean buy = type == SignalPayload.Type.BUY;
        // Entry zone is half an ATR wide around the last close — a single price would imply a
        // precision intraday data we don't have cannot support.
        BigDecimal half = atrBd.multiply(new BigDecimal("0.25"));

        BigDecimal entryLow  = price.subtract(half).setScale(2, RoundingMode.HALF_UP);
        BigDecimal entryHigh = price.add(half).setScale(2, RoundingMode.HALF_UP);
        BigDecimal stop      = (buy ? price.subtract(stopDist) : price.add(stopDist)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal target    = (buy ? price.add(targetDist) : price.subtract(targetDist)).setScale(2, RoundingMode.HALF_UP);

        BigDecimal risk = price.subtract(stop).abs();
        BigDecimal reward = target.subtract(price).abs();
        BigDecimal rr = risk.compareTo(BigDecimal.ZERO) > 0
            ? reward.divide(risk, 2, RoundingMode.HALF_UP) : null;

        return SignalPayload.Execution.builder()
            .entryLow(entryLow).entryHigh(entryHigh)
            .stopLoss(stop).takeProfit(target)
            .riskRewardRatio(rr).atr(atrBd).atrPeriod(14)
            .atrMultipleStop(ATR_STOP_MULTIPLE).atrMultipleTarget(ATR_TARGET_MULTIPLE)
            .basis(String.format("Stop %s×ATR(14)=%s from entry; target %s×ATR for %s:1 R:R.",
                ATR_STOP_MULTIPLE, stopDist.setScale(2, RoundingMode.HALF_UP), ATR_TARGET_MULTIPLE,
                rr != null ? rr.toPlainString() : "—"))
            .positionSizing(SignalPayload.PositionSizing.builder()
                .suggestedAmount(null)
                .limitedBy("NOT_SIZED")
                .basis("Position size needs an equity figure and a tracked cash balance; "
                     + "sizing without one would be invented.")
                .build())
            .build();
    }

    private SignalPayload insufficient(String symbol, List<PriceHistory> bars,
                                       List<SignalPayload.Guardrail> guardrails, String why) {
        return SignalPayload.builder()
            .symbol(symbol)
            .asOf(bars.isEmpty() ? null : barTime(bars.get(bars.size() - 1)))
            .priceAtSignal(bars.isEmpty() ? null : bars.get(bars.size() - 1).getClose())
            .signal(SignalPayload.Type.INSUFFICIENT_DATA)
            .confidence(null).confidenceCeiling(0)
            .dataQuality(DataQuality.INSUFFICIENT.wire())
            .timeframesUsed(new ArrayList<>()).timeframesMissing(new ArrayList<>())
            .factors(new LinkedHashMap<>())
            .guardrails(guardrails)
            .rationale(Collections.singletonList(why))
            .engineVersion(VERSION)
            .build();
    }

    /* ── helpers ── */

    private static SignalPayload.Factor factor(Integer score, double weight, String tf,
                                               String detail, String reason) {
        return SignalPayload.Factor.builder()
            .score(score).weight(score == null ? 0 : weight)
            .timeframe(tf).detail(detail).reason(reason).build();
    }

    private static LocalDateTime barTime(PriceHistory bar) {
        if (bar == null) return null;
        if (bar.getBarStart() != null) return bar.getBarStart();
        return bar.getDate() == null ? null : bar.getDate().atStartOfDay();
    }

    /** Repository returns newest-first; indicators expect oldest-first. */
    private static List<PriceHistory> ascending(List<PriceHistory> desc) {
        if (desc == null) return new ArrayList<>();
        List<PriceHistory> copy = new ArrayList<>(desc);
        Collections.reverse(copy);
        return copy;
    }

    private static List<Double> closes(List<PriceHistory> bars) {
        List<Double> out = new ArrayList<>(bars.size());
        for (PriceHistory b : bars) if (b.getClose() != null) out.add(b.getClose().doubleValue());
        return out;
    }

    /** Latest volume as a multiple of the trailing 20-bar average, or null if unknown. */
    private static Double volumeRatio(List<PriceHistory> bars) {
        if (bars.size() < 21) return null;
        long sum = 0; int n = 0;
        for (int i = bars.size() - 21; i < bars.size() - 1; i++) {
            Long v = bars.get(i).getVolume();
            if (v != null && v > 0) { sum += v; n++; }
        }
        Long latest = bars.get(bars.size() - 1).getVolume();
        if (n == 0 || latest == null || latest <= 0) return null;
        double avg = (double) sum / n;
        return avg <= 0 ? null : latest / avg;
    }

    private static int clamp(int v) { return Math.max(-100, Math.min(100, v)); }
    private static String signed(int v) { return (v >= 0 ? "+" : "") + v; }
}
