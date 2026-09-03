package com.marketai.recommendation.service;

import com.marketai.analyst.dto.AnalystAssessment;
import com.marketai.analyst.service.AnalystService;
import com.marketai.market.entity.PriceHistory;
import com.marketai.market.repository.PriceHistoryRepository;
import com.marketai.market.service.MarketDataService;
import com.marketai.recommendation.dto.MfRecommendationRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The single source of truth for BUY/HOLD/SELL and the advisor-style next action.
 * Every consumer (Portfolio holdings badge, Research/Analyst panel, AI Advisor,
 * Watchlist) must call this instead of hitting TechnicalIndicatorService or
 * AnalystService directly — that fragmentation is exactly why the same stock
 * could show SELL on one tab and BUY on another.
 *
 * Wraps {@link AnalystService#assess} (reused, not reimplemented) and adds:
 *   - confidenceScore: 0..100, how strongly the composite score leans either way
 *   - nextAction: the portfolio-context action (ACCUMULATE/CONTINUE/BOOK_PROFIT/EXIT/REVIEW/HOLD),
 *     replacing the ad-hoc thresholds that used to live in the frontend's Tab9AiAdvisor.decide().
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecommendationEngine {

    private static final String NIFTY_SYMBOL = "^NSEI";
    private static final BigDecimal STCG_RATE = BigDecimal.valueOf(0.15);   // equity MF, <1yr, flat 15%
    private static final BigDecimal LTCG_RATE = BigDecimal.valueOf(0.125);  // equity MF, >=1yr, 12.5% over exemption
    private static final BigDecimal LTCG_EXEMPTION = BigDecimal.valueOf(125000); // per FY, simplified (not tracked across redemptions here)

    private final AnalystService analystService;
    private final PriceHistoryRepository priceHistoryRepository;
    private final MarketDataService marketDataService;

    /** Watchlist/Research use: no position held, so no profit/loss context. */
    public AnalystAssessment recommend(String symbol, String displayName) {
        return recommend(symbol, displayName, null, null, null);
    }

    public AnalystAssessment recommend(String symbol, String displayName, Double pnlPercent) {
        return recommend(symbol, displayName, pnlPercent, null, null);
    }

    /**
     * @param pnlPercent the caller's unrealised P&L% on this symbol if they hold it, else null.
     * @param holdingValue this position's current value, for the portfolio-concentration factor.
     * @param totalPortfolioValue total stock-portfolio value, ditto. Both optional — cheap
     *                   portfolio-context inputs the frontend already has in memory per holding,
     *                   avoiding a DB lookup here.
     */
    public AnalystAssessment recommend(String symbol, String displayName, Double pnlPercent, Double holdingValue, Double totalPortfolioValue) {
        AnalystAssessment a = analystService.assess(symbol, displayName);
        if (a == null) {
            log.warn("analystService.assess returned null for {} — returning minimal assessment", symbol);
            a = AnalystAssessment.builder()
                .symbol(symbol).displayName(displayName != null ? displayName : symbol)
                .compositeScore(0).rating("HOLD").confidenceScore(0)
                .factorBreakdown(new ArrayList<>()).positives(new ArrayList<>()).risks(new ArrayList<>())
                .nextAction("HOLD").nextActionReason("Assessment unavailable — defaulting to HOLD.")
                .build();
            return a;
        }

        // Not enough verifiable data behind this symbol → refuse to emit an action. Showing
        // HOLD here would be indistinguishable from a real "nothing to do" verdict, so the
        // UI needs to be able to tell the two apart.
        if ("INSUFFICIENT".equals(a.getDataQuality())) {
            a.setConfidenceScore(0);
            a.setNextAction("INSUFFICIENT_DATA");
            a.setNextActionReason(String.format(
                "Not enough stored price history for %s to compute indicators (%d day(s) available). No recommendation is shown rather than guessing one. Use Refresh/Rebuild to fetch history, then re-check.",
                symbol, a.getBarsAvailable() != null ? a.getBarsAvailable() : 0));
            return a;
        }

        // Portfolio-position/concentration risk — a real factor in the "multiple factors"
        // sense (position sizing risk), reported for transparency but weightPct=0 so it never
        // silently shifts the market-based composite score; it only feeds risks/nextAction.
        if (holdingValue != null && totalPortfolioValue != null && totalPortfolioValue > 0) {
            double concentration = holdingValue / totalPortfolioValue * 100;
            String note = String.format("%.0f%% of your stock portfolio is in this one position", concentration);
            a.getFactorBreakdown().add(AnalystAssessment.Factor.builder()
                .name("Portfolio Risk").score(concentration > 25 ? -30 : 0).weightPct(0).note(note).build());
            if (concentration > 25) a.getRisks().add(note + " — concentration risk");
        }

        int confidence = Math.abs(a.getCompositeScore());
        String trend = a.getFundamentals() != null && a.getFundamentals().getTrend() != null
            ? a.getFundamentals().getTrend() : "SIDEWAYS";
        NextActionDecision decision = decideNextAction(a.getRating(), a.getCompositeScore(), trend, pnlPercent);

        a.setConfidenceScore(confidence);
        a.setNextAction(decision.kind);
        a.setNextActionReason(decision.reason);
        return a;
    }

    private static class NextActionDecision {
        final String kind, reason;
        NextActionDecision(String kind, String reason) { this.kind = kind; this.reason = reason; }
    }

    /**
     * Mirrors the logic that used to live in the frontend's Tab9AiAdvisor.decide(), moved
     * here so every UI surface gets the same answer. Driven by the composite `rating`
     * (not a raw technical-only signal) so this can never disagree with what the
     * Research/Analyst panel is showing for the same symbol. Every branch below returns a
     * plain-English `reason` alongside the label — this is the piece that was previously
     * missing entirely, making the "next action" look like an unexplained black box.
     */
    private NextActionDecision decideNextAction(String rating, int compositeScore, String trend, Double pnlPercent) {
        boolean strongDown = trend != null && trend.contains("STRONG_DOWNTREND");
        boolean down = trend != null && trend.contains("DOWNTREND");
        boolean up = trend != null && trend.contains("UPTREND");
        boolean hasPosition = pnlPercent != null;
        boolean inProfit = pnlPercent == null || pnlPercent >= 0; // no position → treat neutrally
        String trendLabel = trend != null ? trend.replace('_', ' ').toLowerCase() : "sideways";

        if (strongDown) {
            return new NextActionDecision("EXIT", hasPosition
                ? String.format("Strong downtrend (composite score %d) — %s. Limiting further downside takes priority over the trend's severity.",
                    compositeScore, inProfit ? String.format("protect your +%.1f%% gain now", pnlPercent) : String.format("cut losses before %.1f%% becomes worse", pnlPercent))
                : String.format("Strong downtrend (composite score %d) — avoid entering until it stabilises.", compositeScore));
        }
        if ("SELL".equals(rating)) {
            return inProfit
                ? new NextActionDecision("BOOK_PROFIT", String.format("Composite score %d is SELL-range while you're %s — book some profit rather than fully exiting.",
                    compositeScore, hasPosition ? String.format("up %.1f%%", pnlPercent) : "in a neutral position"))
                : new NextActionDecision("REVIEW", String.format("Composite score %d is SELL-range and you're down %.1f%% — re-check the original thesis before adding more.", compositeScore, pnlPercent));
        }
        if ("BUY".equals(rating) && up) {
            return new NextActionDecision("ACCUMULATE", String.format("Composite score %d is BUY-range and the trend is %s — momentum and the model agree.", compositeScore, trendLabel));
        }
        if (up) {
            return new NextActionDecision("CONTINUE", String.format("Trend is %s (composite score %d, not yet BUY-range) — ride it, no new action needed.", trendLabel, compositeScore));
        }
        if (down) {
            return new NextActionDecision("REVIEW", String.format("Trend is %s (composite score %d) — not a SELL yet, but weak enough to set a stop-loss.", trendLabel, compositeScore));
        }
        return new NextActionDecision("HOLD", String.format("Composite score %d is in neutral range (%s) — nothing about this stock warrants action today.", compositeScore, trendLabel));
    }

    /**
     * Mutual funds don't use the stock engine — a fund's tax treatment (STCG/LTCG),
     * holding-period patience, and portfolio-concentration risk matter far more than
     * technical/momentum signals meant for individual equities. Rule-based and fully
     * transparent: every branch below is cited in the returned reasoning/taxImpact text,
     * not a black-box score.
     */
    public AnalystAssessment recommendMf(MfRecommendationRequest req) {
        BigDecimal invested = req.getInvestedValue() != null ? req.getInvestedValue() : BigDecimal.ZERO;
        BigDecimal current  = req.getCurrentValue()  != null ? req.getCurrentValue()  : invested;
        double pnlPercent = invested.compareTo(BigDecimal.ZERO) > 0
            ? current.subtract(invested).divide(invested, 4, RoundingMode.HALF_UP).doubleValue() * 100 : 0;
        double xirr = req.getXirr() != null ? req.getXirr().doubleValue() : Double.NaN;
        long daysHeld = req.getBuyDate() != null ? ChronoUnit.DAYS.between(req.getBuyDate(), LocalDate.now()) : Long.MAX_VALUE;
        boolean isLongTerm = daysHeld >= 365;

        List<String> positives = new ArrayList<>();
        List<String> risks = new ArrayList<>();
        List<AnalystAssessment.Factor> factors = new ArrayList<>();
        String nextAction;
        String nextActionReason;
        int confidence;

        factors.add(AnalystAssessment.Factor.builder().name("Holding period").score(0).weightPct(0)
            .note(String.format("%d days held (%s)", daysHeld == Long.MAX_VALUE ? 0 : daysHeld, isLongTerm ? "long-term" : "short-term")).build());

        if (daysHeld < 90) {
            // Too early — XIRR is noisy on a very young position, don't act on it yet.
            nextAction = "CONTINUE_SIP";
            confidence = 40;
            nextActionReason = String.format("Only %d days held — XIRR is too noisy this early to act on; keep contributing on schedule.", daysHeld);
            positives.add("Recently started — too early to judge performance");
        } else if (pnlPercent >= 20 && !isLongTerm) {
            // The exact scenario the user described: strong short-term gain, tax-aware
            // decision instead of a flat "Sell".
            nextAction = "PARTIAL_PROFIT_BOOKING";
            confidence = 70;
            long daysToLtcg = 365 - daysHeld;
            nextActionReason = String.format("Up %.1f%% but still short-term (%d days held, %d to go for LTCG) — book part of the gain now and let the rest season into long-term tax treatment.", pnlPercent, daysHeld, daysToLtcg);
            positives.add(String.format("Up %.1f%% — strong short-term gain", pnlPercent));
            risks.add(String.format("Still short-term (%d days held) — STCG applies if redeemed now", daysHeld));
            factors.add(AnalystAssessment.Factor.builder().name("Tax timing").score(-20).weightPct(0)
                .note(String.format("%d more days to reach LTCG treatment", daysToLtcg)).build());
        } else if ((!Double.isNaN(xirr) && xirr < 0) || pnlPercent < -8) {
            nextAction = "SWITCH_FUND";
            confidence = 65;
            long displayDays = daysHeld == Long.MAX_VALUE ? 0 : daysHeld;
            nextActionReason = (!Double.isNaN(xirr) && xirr < 0)
                ? String.format("XIRR of %.1f%% over %d days held is negative/weak — this fund isn't compounding; a switch is worth considering.", xirr, displayDays)
                : String.format("Down %.1f%% overall — underperforming enough to reconsider this fund.", pnlPercent);
            risks.add(String.format("Underperforming (%s)", !Double.isNaN(xirr) ? String.format("XIRR %.1f%%", xirr) : String.format("%.1f%% return", pnlPercent)));
        } else if (!Double.isNaN(xirr) && xirr >= 15 && isLongTerm) {
            nextAction = "INCREASE_SIP";
            confidence = 75;
            nextActionReason = String.format("XIRR of %.1f%% over %d days held (long-term) is strong compounding — worth increasing the monthly contribution.", xirr, daysHeld == Long.MAX_VALUE ? 0 : daysHeld);
            positives.add(String.format("Strong compounding (XIRR %.1f%%) over a long holding period", xirr));
        } else if (!Double.isNaN(xirr) && xirr >= 8) {
            nextAction = "CONTINUE_SIP";
            confidence = 55;
            nextActionReason = String.format("XIRR of %.1f%% is on track — no reason to change the SIP amount either way.", xirr);
            positives.add(String.format("On track (XIRR %.1f%%)", xirr));
        } else {
            nextAction = "HOLD";
            confidence = 45;
            nextActionReason = "No XIRR data and return is unremarkable either way — nothing here warrants a change today.";
        }

        // Concentration check — flagged as a risk alongside whatever the primary action is,
        // not as a replacement for it.
        if (req.getTotalMfPortfolioValue() != null && req.getTotalMfPortfolioValue().compareTo(BigDecimal.ZERO) > 0
                && current.compareTo(BigDecimal.ZERO) > 0) {
            double concentration = current.divide(req.getTotalMfPortfolioValue(), 4, RoundingMode.HALF_UP).doubleValue() * 100;
            if (concentration > 25) {
                risks.add(String.format("%.0f%% of your mutual fund portfolio is in this one fund — concentration risk", concentration));
                if ("HOLD".equals(nextAction) || "CONTINUE_SIP".equals(nextAction)) {
                    nextAction = "REBALANCE";
                    nextActionReason = String.format("%.0f%% of your MF portfolio sits in this one fund — that concentration risk now outweighs the otherwise fine performance.", concentration);
                }
            }
        }

        // Real Nifty 50 benchmark comparison (was hardcoded on the Tab6 UI before this).
        Double niftyTrailingReturn = trailingIndexReturn(NIFTY_SYMBOL);
        if (niftyTrailingReturn != null) {
            double diff = pnlPercent - niftyTrailingReturn;
            factors.add(AnalystAssessment.Factor.builder().name("Benchmark").score((int) Math.max(-100, Math.min(100, diff * 4))).weightPct(0)
                .note(String.format("Nifty 50 trailing return %.1f%%", niftyTrailingReturn)).build());
            if (diff >= 2) positives.add(String.format("Beating Nifty 50's trailing return by %.1f pts", diff));
            else if (diff <= -2) risks.add(String.format("Underperforming Nifty 50's trailing return by %.1f pts", Math.abs(diff)));
        }

        String taxImpact = taxImpactText(current.subtract(invested), daysHeld, isLongTerm);

        String basis = String.format(
            "Rule-based MF assessment: %d days held (%s), XIRR %s, return %.1f%%%s. Not investment advice.",
            daysHeld == Long.MAX_VALUE ? 0 : daysHeld, isLongTerm ? "long-term" : "short-term",
            !Double.isNaN(xirr) ? String.format("%.1f%%", xirr) : "unavailable", pnlPercent,
            niftyTrailingReturn != null ? String.format(", vs Nifty 50 %.1f%%", niftyTrailingReturn) : "");

        return AnalystAssessment.builder()
            .symbol(req.getSymbol()).displayName(req.getFundName() != null ? req.getFundName() : req.getSymbol())
            .price(current.doubleValue())
            .rating(pnlPercent >= 0 ? "BUY" : "HOLD") // coarse BUY/HOLD/SELL kept for consistency with the shared DTO; nextAction is the real answer for MFs
            .conviction(confidence >= 65 ? "HIGH" : confidence >= 45 ? "MEDIUM" : "LOW")
            .compositeScore((int) Math.round(pnlPercent))
            .factorBreakdown(factors)
            .positives(positives).risks(risks)
            .confidenceScore(confidence)
            .nextAction(nextAction)
            .nextActionReason(nextActionReason)
            .taxImpact(taxImpact)
            .basis(basis)
            .build();
    }

    private String taxImpactText(BigDecimal gain, long daysHeld, boolean isLongTerm) {
        if (gain.compareTo(BigDecimal.ZERO) <= 0) return "No tax impact — position is at a loss.";
        if (isLongTerm) {
            BigDecimal taxable = gain.subtract(LTCG_EXEMPTION).max(BigDecimal.ZERO);
            BigDecimal tax = taxable.multiply(LTCG_RATE).setScale(0, RoundingMode.HALF_UP);
            return String.format("LTCG: ₹%,.0f tax if redeemed today (12.5%% over ₹1.25L/FY exemption).", tax);
        }
        BigDecimal tax = gain.multiply(STCG_RATE).setScale(0, RoundingMode.HALF_UP);
        long daysToLtcg = Math.max(0, 365 - daysHeld);
        return String.format("STCG: ₹%,.0f tax (15%%) if redeemed today. LTCG treatment in %d more day%s.",
            tax, daysToLtcg, daysToLtcg == 1 ? "" : "s");
    }

    /** Real trailing returns for the three benchmark indices — replaces what used to be
     *  hardcoded "+14.2%"/"+13.8%"/"+11.5%" literals on the Tab6 Mutual Funds UI. */
    public java.util.Map<String, Double> getIndexBenchmarks() {
        java.util.Map<String, Double> out = new java.util.LinkedHashMap<>();
        out.put("nifty50", trailingIndexReturn("^NSEI"));
        out.put("sensex", trailingIndexReturn("^BSESN"));
        out.put("bankNifty", trailingIndexReturn("^NSEBANK"));
        return out;
    }

    /** Trailing return over the available stored price history (fetches if missing), as a
     *  real substitute for what used to be a hardcoded "+14.2%" string on the Tab6 UI. */
    private Double trailingIndexReturn(String symbol) {
        try {
            List<PriceHistory> history = priceHistoryRepository.findTop200BySymbolOrderByDateDesc(symbol);
            if (history.size() < 20) {
                marketDataService.fetchAndStorePriceHistory(symbol, "1y");
                history = priceHistoryRepository.findTop200BySymbolOrderByDateDesc(symbol);
            }
            if (history.size() < 20) return null;
            history.sort(Comparator.comparing(PriceHistory::getDate));
            BigDecimal first = history.get(0).getClose();
            BigDecimal last = history.get(history.size() - 1).getClose();
            if (first == null || last == null || first.compareTo(BigDecimal.ZERO) <= 0) return null;
            return last.subtract(first).divide(first, 4, RoundingMode.HALF_UP).doubleValue() * 100;
        } catch (Exception e) {
            log.debug("Could not compute trailing index return for {}: {}", symbol, e.getMessage());
            return null;
        }
    }
}
