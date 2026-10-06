package com.marketai.signal.service;

import com.marketai.forecast.model.Stats;
import com.marketai.market.entity.PriceHistory;
import com.marketai.market.quality.DailySeries;
import com.marketai.market.quality.SeriesStatus;
import com.marketai.market.service.MarketDataService;
import com.marketai.signal.dto.SignalPayload;
import com.marketai.technical.service.Indicators;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import com.marketai.common.quality.DataQuality;

/**
 * Multi-factor signal rule, and the evidence of whether it works.
 *
 * The rule scores independent factor families once each (trend, momentum, market structure,
 * volume, volatility) so correlated oscillators cannot stack up. Its weights and ±20 thresholds
 * are design choices, not fitted values — so the rule is not trusted on its own. Every request
 * also replays the rule over the instrument's own history (walk-forward, each past call made
 * from the 200 bars up to that day only) and measures what followed each BUY and SELL over
 * {@value #VALIDATION_HORIZON} sessions. The current BUY or SELL is shown only when the lower
 * 95% bound of that call's historical hit rate beats the base rate on at least
 * {@value #MIN_INDEPENDENT_CALLS} independent calls; otherwise the answer is
 * NO_ACTIONABLE_SIGNAL, with the rule's reading and its record shown for transparency.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SignalEngine {

    public static final String VERSION = "signal-engine/3.0.0";

    private final MarketDataService marketDataService;
    private final MarketStructureAnalyzer structureAnalyzer;

    private static final double W_TREND      = 0.25;
    private static final double W_MOMENTUM   = 0.25;
    private static final double W_STRUCTURE  = 0.25;
    private static final double W_VOLUME     = 0.15;
    private static final double W_VOLATILITY = 0.10;

    private static final BigDecimal ATR_STOP_MULTIPLE   = new BigDecimal("2.0");
    private static final BigDecimal ATR_TARGET_MULTIPLE = new BigDecimal("4.0");  // 2R

    private static final double LOW_VOLUME_RATIO = 0.7;
    private static final int BUY_THRESHOLD  = 20;
    private static final int SELL_THRESHOLD = -20;

    /** Bars the rule reads at each evaluation — the same window live and in the replay. */
    static final int RULE_WINDOW = 200;
    static final int MIN_BARS = 50;
    public static final int VALIDATION_HORIZON = 20;
    static final int MIN_INDEPENDENT_CALLS = 30;

    /** The rule's output at one point in time. */
    record Evaluation(SignalPayload.Type type, int composite, int ceiling, Map<String, SignalPayload.Factor> factors,
                      List<String> rationale, List<SignalPayload.Guardrail> guardrails, Double atr) {}

    public SignalPayload analyse(String symbol) {
        DailySeries series = marketDataService.getDailySeries(symbol, MIN_BARS);
        List<PriceHistory> daily = series.bars();
        List<SignalPayload.Guardrail> guardrails = new ArrayList<>();

        if (series.status() == SeriesStatus.INSUFFICIENT_DATA) {
            guardrails.add(guard("INSUFFICIENT_BARS", "SUPPRESSED", daily.size() + " valid daily bars; " + MIN_BARS + " required."));
            return empty(series, SignalPayload.Type.INSUFFICIENT_DATA, guardrails, "Not enough price history to evaluate this symbol.");
        }
        if (series.status() == SeriesStatus.STALE_DATA) {
            guardrails.add(guard("STALE_DATA", "SUPPRESSED", "Newest bar is from " + series.lastBarDate() + ", "
                    + series.sessionsBehind() + " session(s) behind " + series.expectedSession() + "."));
            return empty(series, SignalPayload.Type.STALE_DATA, guardrails, "Price history is stale; no signal is given on old data.");
        }

        Evaluation now = evaluate(daily.subList(Math.max(0, daily.size() - RULE_WINDOW), daily.size()), structureAnalyzer);
        guardrails.addAll(now.guardrails());
        SignalPayload.Validation validation = validate(daily, now.type());

        SignalPayload.Type shown = now.type();
        List<String> rationale = new ArrayList<>(now.rationale());
        if ((shown == SignalPayload.Type.BUY || shown == SignalPayload.Type.SELL) && !validation.isCurrentCallValidated()) {
            guardrails.add(guard("UNVALIDATED_RULE", "SUPPRESSED", validation.getSummary()));
            rationale.add("Rule reads " + shown + ", but that call has no demonstrated edge on this instrument — shown as no actionable signal.");
            shown = SignalPayload.Type.NO_ACTIONABLE_SIGNAL;
        } else if (shown == SignalPayload.Type.HOLD) {
            // The rule's neutral reading is not a validated "hold" call either — it means no signal.
            shown = SignalPayload.Type.NO_ACTIONABLE_SIGNAL;
        }

        BigDecimal lastClose = series.last().getClose();
        boolean actionable = shown == SignalPayload.Type.BUY || shown == SignalPayload.Type.SELL;
        return SignalPayload.builder()
                .symbol(series.symbol())
                .asOf(barTime(series.last()))
                .priceAtSignal(lastClose)
                .signal(shown)
                .ruleOutput(now.type())
                // |composite| is agreement between factor families, not a probability. It is
                // only reported for a validated call.
                .confidence(actionable ? Math.min(Math.abs(now.composite()), now.ceiling()) : null)
                .confidenceCeiling(now.ceiling())
                .dataQuality((series.status() == SeriesStatus.OK ? DataQuality.FULL : DataQuality.PARTIAL).wire())
                .timeframesUsed(List.of(Timeframe.D1.code()))
                .timeframesMissing(List.of())
                .factors(now.factors())
                .execution(actionable ? buildExecution(shown, lastClose, now.atr()) : null)
                .guardrails(guardrails)
                .rationale(rationale)
                .validation(validation)
                .engineVersion(VERSION)
                .build();
    }

    /** The rule itself, over bars oldest-first. Pure: the live call and the replay use exactly this. */
    static Evaluation evaluate(List<PriceHistory> bars, MarketStructureAnalyzer structureAnalyzer) {
        Map<String, SignalPayload.Factor> factors = new LinkedHashMap<>();
        List<String> rationale = new ArrayList<>();
        List<SignalPayload.Guardrail> guardrails = new ArrayList<>();
        List<Double> closes = new ArrayList<>(bars.size());
        for (PriceHistory b : bars) closes.add(b.getClose().doubleValue());
        double price = closes.get(closes.size() - 1);

        /* Trend */
        Double sma20 = Indicators.sma(closes, 20), sma50 = Indicators.sma(closes, 50);
        Integer trendScore = null;
        String trendDetail = null;
        if (sma20 != null && sma50 != null) {
            int s = (price > sma20 ? 30 : -30) + (sma20 > sma50 ? 40 : -40);
            trendScore = clamp(s);
            trendDetail = String.format("Price %.2f vs SMA20 %.2f and SMA50 %.2f.", price, sma20, sma50);
            rationale.add("Daily trend: " + trendDetail + " (" + signed(trendScore) + ")");
        }
        factors.put("trend", factor(trendScore, W_TREND, trendDetail, trendScore == null ? "Moving averages could not be computed." : null));

        /* Momentum — one vote for the family */
        Double rsi = Indicators.rsi(closes, 14);
        double[] macd = Indicators.macd(closes, 12, 26, 9);
        Integer momentumScore = null;
        String momentumDetail = null;
        if (rsi != null && macd != null) {
            int s = rsi > 70 ? -30 : rsi < 30 ? 30 : rsi > 55 ? 15 : rsi < 45 ? -15 : 0;
            s += macd[2] > 0 ? 25 : -25;
            momentumScore = clamp(s);
            momentumDetail = String.format("RSI(14) %.1f, MACD histogram %.3f.", rsi, macd[2]);
            rationale.add("Momentum: " + momentumDetail + " (" + signed(momentumScore) + ")");
        }
        factors.put("momentum", factor(momentumScore, W_MOMENTUM, momentumDetail, momentumScore == null ? "RSI/MACD could not be computed." : null));

        /* Market structure */
        MarketStructureAnalyzer.StructureRead structure = structureAnalyzer.analyse(bars);
        factors.put("marketStructure", factor(structure.getScore(), W_STRUCTURE, structure.getDetail(),
                structure.getScore() == null ? "Not enough swing points to read structure." : null));
        if (structure.getScore() != null) rationale.add("Structure: " + structure.getDetail() + " (" + signed(structure.getScore()) + ")");

        /* Volume */
        Integer volumeScore = null;
        String volumeDetail = null;
        Double volumeRatio = volumeRatio(bars);
        if (volumeRatio != null) {
            volumeScore = clamp((int) Math.round((volumeRatio - 1.0) * 60));
            volumeDetail = String.format("Latest volume %.2fx its 20-bar average.", volumeRatio);
            rationale.add("Volume: " + volumeDetail + " (" + signed(volumeScore) + ")");
        }
        factors.put("volume", factor(volumeScore, W_VOLUME, volumeDetail, volumeScore == null ? "Volume history unavailable." : null));

        /* Volatility — not directional; lowers conviction when high */
        Double atr = Indicators.atr(bars, 14);
        Integer volatilityScore = null;
        String volatilityDetail = null;
        if (atr != null) {
            double atrPct = atr / price * 100;
            volatilityScore = atrPct > 5 ? -25 : atrPct > 3 ? -10 : 10;
            volatilityDetail = String.format("ATR(14) is %.2f%% of price.", atrPct);
            rationale.add("Volatility: " + volatilityDetail + " (" + signed(volatilityScore) + ")");
        }
        factors.put("volatility", factor(volatilityScore, W_VOLATILITY, volatilityDetail, volatilityScore == null ? "ATR could not be computed." : null));

        double wsum = 0, score = 0;
        for (SignalPayload.Factor f : factors.values()) {
            if (f.getScore() == null) continue;
            wsum += f.getWeight();
            score += f.getScore() * f.getWeight();
        }
        int composite = wsum > 0 ? clamp((int) Math.round(score / wsum)) : 0;
        int ceiling = (int) Math.round(wsum * 100);

        SignalPayload.Type type = composite >= BUY_THRESHOLD ? SignalPayload.Type.BUY
                : composite <= SELL_THRESHOLD ? SignalPayload.Type.SELL : SignalPayload.Type.HOLD;
        if (type == SignalPayload.Type.BUY && volumeRatio != null && volumeRatio < LOW_VOLUME_RATIO) {
            type = SignalPayload.Type.HOLD;
            guardrails.add(guard("LOW_VOLUME_TRAP", "SUPPRESSED", String.format("Volume is %.2fx average — too thin to trust a breakout.", volumeRatio)));
            rationale.add("Downgraded to HOLD: breakout is not backed by volume.");
        }
        if (type == SignalPayload.Type.BUY && structure.getStructure() == MarketStructureAnalyzer.Structure.DOWNTREND) {
            type = SignalPayload.Type.HOLD;
            guardrails.add(guard("COUNTER_TREND", "SUPPRESSED", "Buy signal contradicts a confirmed downtrend in market structure."));
            rationale.add("Downgraded to HOLD: signal runs against prevailing structure.");
        }
        return new Evaluation(type, composite, ceiling, factors, rationale, guardrails, atr);
    }

    /**
     * Replays the rule at every past session with the {@value #RULE_WINDOW} bars up to that
     * session, and measures the close {@value #VALIDATION_HORIZON} sessions later.
     */
    SignalPayload.Validation validate(List<PriceHistory> bars, SignalPayload.Type current) {
        int h = VALIDATION_HORIZON;
        int buys = 0, buyUp = 0, sells = 0, sellDown = 0, obs = 0, ups = 0;
        // A call made on consecutive days looks at nearly the same {h}-session window, so counting
        // each day would be counting one outcome many times. Calls are counted only when the last
        // counted call of that kind is at least h sessions behind, which makes them independent.
        int lastBuy = Integer.MIN_VALUE / 2, lastSell = Integer.MIN_VALUE / 2;
        double rBuy = 0, rSell = 0, rAll = 0;
        for (int t = RULE_WINDOW - 1; t + h < bars.size(); t++) {
            Evaluation e = evaluate(bars.subList(t - RULE_WINDOW + 1, t + 1), structureAnalyzer);
            double ret = Math.log(bars.get(t + h).getClose().doubleValue() / bars.get(t).getClose().doubleValue());
            obs++;
            rAll += ret;
            if (ret > 0) ups++;
            if (e.type() == SignalPayload.Type.BUY && t - lastBuy >= h) { lastBuy = t; buys++; rBuy += ret; if (ret > 0) buyUp++; }
            else if (e.type() == SignalPayload.Type.SELL && t - lastSell >= h) { lastSell = t; sells++; rSell += ret; if (ret < 0) sellDown++; }
        }
        double base = obs > 0 ? (double) ups / obs : 0.5;
        Double buyHit = buys > 0 ? (double) buyUp / buys : null, sellHit = sells > 0 ? (double) sellDown / sells : null;
        Double buyLo = buyHit != null ? Stats.wilson(buyHit, Math.max(1, buys), 1.96)[0] : null;
        Double sellLo = sellHit != null ? Stats.wilson(sellHit, Math.max(1, sells), 1.96)[0] : null;
        boolean validated = switch (current) {
            case BUY -> buys >= MIN_INDEPENDENT_CALLS && buyLo != null && buyLo > base;
            case SELL -> sells >= MIN_INDEPENDENT_CALLS && sellLo != null && sellLo > 1 - base;
            default -> false;
        };
        String summary = obs == 0 ? "Not enough history to replay the rule."
                : String.format("Over %d past sessions: %d independent BUY calls (%s ended higher after %d sessions), %d independent SELL calls (%s ended lower); base rate %.0f%% higher.",
                    obs, buys, pct(buyHit), h, sells, pct(sellHit), base * 100);
        return SignalPayload.Validation.builder()
                .horizonSessions(h).observations(obs).buyCalls(buys).sellCalls(sells)
                .buyHitRate(r4(buyHit)).buyHitRateCiLow(r4(buyLo)).sellHitRate(r4(sellHit)).sellHitRateCiLow(r4(sellLo))
                .baseUpRate(r4(base))
                .meanForwardReturnBuyPct(buys > 0 ? r4((Math.exp(rBuy / buys) - 1) * 100) : null)
                .meanForwardReturnSellPct(sells > 0 ? r4((Math.exp(rSell / sells) - 1) * 100) : null)
                .meanForwardReturnAllPct(obs > 0 ? r4((Math.exp(rAll / obs) - 1) * 100) : 0)
                .currentCallValidated(validated)
                .summary(summary)
                .build();
    }

    /** ATR-derived entry, stop and target — only for a validated call. 2×ATR stop, 2:1 target by construction. */
    private SignalPayload.Execution buildExecution(SignalPayload.Type type, BigDecimal price, Double atr) {
        if (price == null || atr == null || atr <= 0) return null;
        BigDecimal atrBd = BigDecimal.valueOf(atr).setScale(2, RoundingMode.HALF_UP);
        BigDecimal stopDist = atrBd.multiply(ATR_STOP_MULTIPLE), targetDist = atrBd.multiply(ATR_TARGET_MULTIPLE);
        boolean buy = type == SignalPayload.Type.BUY;
        BigDecimal half = atrBd.multiply(new BigDecimal("0.25"));
        BigDecimal stop = (buy ? price.subtract(stopDist) : price.add(stopDist)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal target = (buy ? price.add(targetDist) : price.subtract(targetDist)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal risk = price.subtract(stop).abs(), reward = target.subtract(price).abs();
        return SignalPayload.Execution.builder()
                .entryLow(price.subtract(half).setScale(2, RoundingMode.HALF_UP)).entryHigh(price.add(half).setScale(2, RoundingMode.HALF_UP))
                .stopLoss(stop).takeProfit(target)
                .riskRewardRatio(risk.signum() > 0 ? reward.divide(risk, 2, RoundingMode.HALF_UP) : null)
                .atr(atrBd).atrPeriod(14).atrMultipleStop(ATR_STOP_MULTIPLE).atrMultipleTarget(ATR_TARGET_MULTIPLE)
                .basis(String.format("Stop %s×ATR(14)=%s from entry; target %s×ATR (2:1 by construction).",
                        ATR_STOP_MULTIPLE, stopDist.setScale(2, RoundingMode.HALF_UP), ATR_TARGET_MULTIPLE))
                .positionSizing(SignalPayload.PositionSizing.builder().limitedBy("NOT_SIZED")
                        .basis("Position size needs an equity figure and a tracked cash balance; sizing without one would be invented.").build())
                .build();
    }

    private SignalPayload empty(DailySeries s, SignalPayload.Type type, List<SignalPayload.Guardrail> guardrails, String why) {
        return SignalPayload.builder()
                .symbol(s.symbol())
                .asOf(s.isEmpty() ? null : barTime(s.last()))
                .priceAtSignal(s.isEmpty() ? null : s.last().getClose())
                .signal(type).ruleOutput(type)
                .confidence(null).confidenceCeiling(0)
                .dataQuality(DataQuality.INSUFFICIENT.wire())
                .timeframesUsed(new ArrayList<>()).timeframesMissing(new ArrayList<>())
                .factors(new LinkedHashMap<>())
                .guardrails(guardrails)
                .rationale(Collections.singletonList(why))
                .engineVersion(VERSION)
                .build();
    }

    private static SignalPayload.Guardrail guard(String rule, String action, String detail) {
        return SignalPayload.Guardrail.builder().rule(rule).action(action).detail(detail).build();
    }

    private static SignalPayload.Factor factor(Integer score, double weight, String detail, String reason) {
        return SignalPayload.Factor.builder().score(score).weight(score == null ? 0 : weight)
                .timeframe(Timeframe.D1.code()).detail(detail).reason(reason).build();
    }

    private static LocalDateTime barTime(PriceHistory bar) {
        if (bar == null) return null;
        if (bar.getBarStart() != null) return bar.getBarStart();
        return bar.getDate() == null ? null : bar.getDate().atStartOfDay();
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
        return latest / ((double) sum / n);
    }

    private static String pct(Double v) { return v == null ? "—" : String.format("%.0f%%", v * 100); }
    private static Double r4(Double v) { return v == null ? null : Math.round(v * 10000.0) / 10000.0; }
    private static int clamp(int v) { return Math.max(-100, Math.min(100, v)); }
    private static String signed(int v) { return (v >= 0 ? "+" : "") + v; }
}
