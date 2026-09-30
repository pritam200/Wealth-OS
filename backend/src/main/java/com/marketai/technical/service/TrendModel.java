package com.marketai.technical.service;

import com.marketai.technical.dto.TrendAssessment;
import com.marketai.technical.dto.TrendAssessment.Evidence;

import java.util.ArrayList;
import java.util.List;

/**
 * Evidence-based trend classification.
 *
 * Six direction votes, each BULLISH, BEARISH or NEUTRAL (or UNAVAILABLE when there is too little
 * history for it): price vs SMA50, price vs SMA200, SMA50 vs SMA200, the 20-session slope of
 * SMA50 (flat within ±{@value #FLAT_SLOPE_PCT}%), EMA20 vs EMA50, and swing structure (higher
 * highs and lows vs lower ones). ADX, RSI, MACD histogram and relative volume are reported as
 * confirmation but do not vote.
 *
 * Rule, over the votes available (at least {@value #MIN_VOTES}):
 *   STRONG_UPTREND   — no bearish vote, at most one neutral, and ADX ≥ {@value #ADX_TRENDING}
 *   UPTREND          — bullish votes ≥ two thirds of those available and at most one bearish
 *   STRONG_DOWNTREND / DOWNTREND mirror these
 *   SIDEWAYS         — otherwise
 * ADX 25 is Wilder's conventional "trending" threshold. The label describes the present; the
 * backtest showed it carries no measurable edge on forward returns, so nothing converts it
 * into a probability or a score.
 */
public final class TrendModel {

    private TrendModel() {}

    static final double FLAT_SLOPE_PCT = 0.5;
    static final double ADX_TRENDING = 25;
    static final int MIN_VOTES = 4;

    public static final String RULE = "Votes: price vs SMA50, price vs SMA200, SMA50 vs SMA200, SMA50 20-session slope (flat within ±0.5%), "
            + "EMA20 vs EMA50, swing structure. STRONG = no opposing vote, ≤1 neutral and ADX ≥ 25; "
            + "UPTREND/DOWNTREND = ≥2/3 of available votes one way and ≤1 against; else SIDEWAYS. Needs ≥4 votes.";

    public record Inputs(double price, Double sma50, Double sma200, Double sma50TwentyAgo, Double ema20, Double ema50,
                         String structure, String structureDetail, Double adx, Double plusDi, Double minusDi,
                         Double rsi, Double macdHistogram, Double relativeVolume) {}

    public static TrendAssessment classify(Inputs in) {
        List<Evidence> ev = new ArrayList<>();
        ev.add(compare("Price vs SMA50", in.price(), in.sma50(), "Price", "SMA50"));
        ev.add(compare("Price vs SMA200", in.price(), in.sma200(), "Price", "SMA200"));
        ev.add(in.sma50() != null && in.sma200() != null
                ? compare("SMA50 vs SMA200", in.sma50(), in.sma200(), "SMA50", "SMA200")
                : unavailable("SMA50 vs SMA200", "Needs 200 sessions."));
        if (in.sma50() != null && in.sma50TwentyAgo() != null) {
            double slope = (in.sma50() / in.sma50TwentyAgo() - 1) * 100;
            String r = slope > FLAT_SLOPE_PCT ? "BULLISH" : slope < -FLAT_SLOPE_PCT ? "BEARISH" : "NEUTRAL";
            ev.add(new Evidence("SMA50 slope", r, String.format("SMA50 %+.2f%% over 20 sessions", slope), true));
        } else {
            ev.add(unavailable("SMA50 slope", "Needs 70 sessions."));
        }
        ev.add(in.ema20() != null && in.ema50() != null
                ? compare("EMA20 vs EMA50", in.ema20(), in.ema50(), "EMA20", "EMA50")
                : unavailable("EMA20 vs EMA50", "Needs 100 sessions."));
        if (in.structure() == null || "INSUFFICIENT_DATA".equals(in.structure())) {
            ev.add(unavailable("Swing structure", "Too few swing points."));
        } else {
            String r = switch (in.structure()) { case "UPTREND" -> "BULLISH"; case "DOWNTREND" -> "BEARISH"; default -> "NEUTRAL"; };
            ev.add(new Evidence("Swing structure", r, in.structureDetail(), true));
        }

        // Confirmation only.
        ev.add(in.adx() != null
                ? new Evidence("ADX(14)", "NEUTRAL", String.format("ADX %.1f (+DI %.1f, −DI %.1f) — %s", in.adx(), in.plusDi(), in.minusDi(),
                    in.adx() >= ADX_TRENDING ? "trending" : in.adx() < 20 ? "no clear trend" : "developing"), false)
                : unavailable("ADX(14)", "Needs 29 sessions.", false));
        ev.add(in.rsi() != null ? new Evidence("RSI(14)", "NEUTRAL", String.format("RSI %.1f", in.rsi()), false)
                : unavailable("RSI(14)", "Needs 15 sessions.", false));
        ev.add(in.macdHistogram() != null ? new Evidence("MACD histogram", "NEUTRAL", String.format("%+.3f", in.macdHistogram()), false)
                : unavailable("MACD histogram", "Needs 34 sessions.", false));
        ev.add(in.relativeVolume() != null ? new Evidence("Relative volume", "NEUTRAL", String.format("%.2fx the 20-session average", in.relativeVolume()), false)
                : unavailable("Relative volume", "No volume data.", false));

        int bull = 0, bear = 0, neutral = 0, n = 0;
        for (Evidence e : ev) {
            if (!e.isVote() || "UNAVAILABLE".equals(e.getReading())) continue;
            n++;
            switch (e.getReading()) { case "BULLISH" -> bull++; case "BEARISH" -> bear++; default -> neutral++; }
        }
        String label;
        if (n < MIN_VOTES) label = "INSUFFICIENT_DATA";
        else {
            boolean trending = in.adx() != null && in.adx() >= ADX_TRENDING;
            int need = (int) Math.ceil(n * 2.0 / 3);
            if (bear == 0 && neutral <= 1 && trending) label = "STRONG_UPTREND";
            else if (bull == 0 && neutral <= 1 && trending) label = "STRONG_DOWNTREND";
            else if (bull >= need && bear <= 1) label = "UPTREND";
            else if (bear >= need && bull <= 1) label = "DOWNTREND";
            else label = "SIDEWAYS";
        }
        return TrendAssessment.builder().label(label).bullishVotes(bull).bearishVotes(bear).votesAvailable(n)
                .evidence(ev).rule(RULE).build();
    }

    private static Evidence compare(String name, double a, Double b, String an, String bn) {
        if (b == null) return unavailable(name, "Not enough history for " + bn + ".");
        String r = a > b ? "BULLISH" : a < b ? "BEARISH" : "NEUTRAL";
        return new Evidence(name, r, String.format("%s %.2f %s %s %.2f", an, a, a > b ? ">" : a < b ? "<" : "=", bn, b), true);
    }

    private static Evidence unavailable(String name, String why) {
        return unavailable(name, why, true);
    }

    private static Evidence unavailable(String name, String why, boolean vote) {
        return new Evidence(name, "UNAVAILABLE", why, vote);
    }
}
