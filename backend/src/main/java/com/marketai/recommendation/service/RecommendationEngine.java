package com.marketai.recommendation.service;

import com.marketai.analyst.dto.AnalystAssessment;
import com.marketai.analyst.service.AnalystService;
import com.marketai.market.entity.PriceHistory;
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
import com.marketai.common.quality.DataQuality;

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
    // One rate table for the whole app. This class used to carry its own STCG of 15% — the
    // pre-July-2024 rate — while tax used 20%, so the same gain was quoted two ways.
    private static final BigDecimal STCG_RATE = com.marketai.tax.lot.CapitalGainsRates.STCG_RATE;
    private static final BigDecimal LTCG_RATE = com.marketai.tax.lot.CapitalGainsRates.LTCG_RATE;
    private static final BigDecimal LTCG_EXEMPTION = com.marketai.tax.lot.CapitalGainsRates.LTCG_EXEMPTION; // per FY; not tracked across redemptions here

    private final AnalystService analystService;
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
            log.warn("analystService.assess returned null for {}", symbol);
            return AnalystAssessment.builder()
                .symbol(symbol).displayName(displayName != null ? displayName : symbol)
                .rating(AnalystService.NO_ACTIONABLE_SIGNAL)
                .factorBreakdown(new ArrayList<>()).positives(new ArrayList<>()).risks(new ArrayList<>())
                .nextAction("INSUFFICIENT_DATA").nextActionReason("Assessment unavailable — no action is suggested.")
                .build();
        }

        if (!DataQuality.of(a.getDataQuality()).isUsable() || "INSUFFICIENT_DATA".equals(a.getRating())) {
            a.setConfidenceScore(null);
            a.setNextAction("INSUFFICIENT_DATA");
            a.setNextActionReason(String.format(
                "Not enough valid price history for %s (%d daily bar(s)). No recommendation is shown rather than guessing one.",
                symbol, a.getBarsAvailable() != null ? a.getBarsAvailable() : 0));
            return a;
        }
        if ("STALE_DATA".equals(a.getRating())) {
            a.setNextAction("STALE_DATA");
            a.setNextActionReason("Price history for " + symbol + " is stale (newest bar " + a.getPriceDate()
                + ") and could not be refreshed. No action is suggested from old data.");
            return a;
        }

        // Portfolio exposure: a diversification guideline (no single stock above 25% of the
        // stock portfolio), not a market call. Reported as its own factor.
        Double concentration = null;
        if (holdingValue != null && totalPortfolioValue != null && totalPortfolioValue > 0) {
            concentration = holdingValue / totalPortfolioValue * 100;
            String note = String.format("%.0f%% of your stock portfolio is in this one position (guideline: at most %d%%)",
                concentration, CONCENTRATION_GUIDELINE_PCT);
            a.getFactorBreakdown().add(AnalystAssessment.Factor.builder()
                .name("Portfolio exposure").category("PORTFOLIO_RISK")
                .reading(String.format("%.0f%%", concentration)).note(note).build());
            if (concentration > CONCENTRATION_GUIDELINE_PCT) a.getRisks().add(note);
        }

        NextActionDecision decision = researchGate(a, decideNextAction(a, pnlPercent, concentration));
        a.setNextAction(decision.kind);
        a.setNextActionReason(decision.reason);
        return a;
    }

    /** Diversification guideline used across the app for a single position. */
    static final int CONCENTRATION_GUIDELINE_PCT = 25;

    private static class NextActionDecision {
        final String kind, reason;
        NextActionDecision(String kind, String reason) { this.kind = kind; this.reason = reason; }
    }

    /** Actions that act on a validated call; research that contradicts the call holds them back. */
    static final java.util.Set<String> CALL_ACTIONS = java.util.Set.of("ACCUMULATE", "AVOID", "BOOK_PROFIT");

    /**
     * Research can stop a call, never start one: when this session's research finds conflicting
     * evidence, a validated-call action becomes REVIEW. Without research, the action stands.
     */
    static NextActionDecision researchGate(AnalystAssessment a, NextActionDecision d) {
        if (!CALL_ACTIONS.contains(d.kind) || !"CONFLICTING_EVIDENCE".equals(a.getResearchActionability())) return d;
        return new NextActionDecision("REVIEW", "Research finds conflicting evidence: " + a.getResearchReason()
            + " The quantitative call alone is not acted on — review the research before trading. (Quant: " + d.reason + ")");
    }

    /**
     * Next action from verified inputs only: a BUY/SELL that has a measured edge on this stock
     * (the rating), the size of the position against the diversification guideline, and the
     * holder's P&L. The trend label no longer triggers EXIT by itself — the backtest showed it
     * has no edge on forward returns, so an EXIT driven by it would be a fabricated call.
     * Without a validated signal the answer is NO_ACTIONABLE_SIGNAL, with the evidence shown.
     */
    private NextActionDecision decideNextAction(AnalystAssessment a, Double pnlPercent, Double concentration) {
        String rating = a.getRating();
        boolean hasPosition = pnlPercent != null;
        boolean overweight = concentration != null && concentration > CONCENTRATION_GUIDELINE_PCT;
        String record = a.getSignalValidation() != null ? a.getSignalValidation().getSummary() : "";
        String hit = a.getConfidenceScore() != null ? a.getConfidenceScore() + "%" : "—";

        if ("BUY".equals(rating)) {
            if (overweight) {
                return new NextActionDecision("HOLD", String.format("The signal reads BUY (right %s of the time on this stock historically), "
                    + "but the position is already %.0f%% of your stock portfolio — above the %d%% guideline, so adding is not suggested.",
                    hit, concentration, CONCENTRATION_GUIDELINE_PCT));
            }
            return new NextActionDecision("ACCUMULATE", String.format("Validated BUY: past BUY calls on this stock were followed by a higher close "
                + "after 20 sessions %s of the time, above the base rate. %s", hit, record));
        }
        if ("SELL".equals(rating)) {
            if (!hasPosition) {
                return new NextActionDecision("AVOID", String.format("Validated SELL (right %s of the time historically) — not a stock to enter now. %s", hit, record));
            }
            return pnlPercent >= 0
                ? new NextActionDecision("BOOK_PROFIT", String.format("Validated SELL (right %s of the time historically) while you're up %.1f%% — consider booking part of the gain. %s", hit, pnlPercent, record))
                : new NextActionDecision("REVIEW", String.format("Validated SELL (right %s of the time historically) and you're down %.1f%% — re-check the original thesis and your stop. %s", hit, Math.abs(pnlPercent), record));
        }
        if (overweight) {
            return new NextActionDecision("REBALANCE", String.format("No validated signal, but this position is %.0f%% of your stock portfolio — above the %d%% guideline. Trimming reduces single-stock risk.",
                concentration, CONCENTRATION_GUIDELINE_PCT));
        }
        String ruleNote = a.getRuleOutput() != null && !AnalystService.NO_ACTIONABLE_SIGNAL.equals(a.getRuleOutput())
            && !"HOLD".equals(a.getRuleOutput())
            ? " The rule reads " + a.getRuleOutput() + ", but that call has not beaten the base rate on this stock." : "";
        return new NextActionDecision(AnalystService.NO_ACTIONABLE_SIGNAL, String.format(
            "No validated signal — nothing in the verified data justifies a trade on its own.%s Trend is %s (descriptive). %s",
            ruleNote, a.getFundamentals() != null ? String.valueOf(a.getFundamentals().getTrend()).toLowerCase().replace('_', ' ') : "unknown", record));
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
        boolean isLongTerm = req.getBuyDate() == null
            || com.marketai.tax.lot.CapitalGainsRates.isLongTerm(req.getBuyDate(), LocalDate.now());
        long daysToLtcgFromBuy = com.marketai.tax.lot.CapitalGainsRates.daysToLongTerm(req.getBuyDate(), LocalDate.now());

        List<String> positives = new ArrayList<>();
        List<String> risks = new ArrayList<>();
        List<AnalystAssessment.Factor> factors = new ArrayList<>();
        String nextAction;
        String nextActionReason;

        factors.add(AnalystAssessment.Factor.builder().name("Holding period").category("HOLDING").weightPct(0)
            .note(String.format("%d days held (%s)", daysHeld == Long.MAX_VALUE ? 0 : daysHeld, isLongTerm ? "long-term" : "short-term")).build());

        if (daysHeld < 90) {
            // Too early — XIRR is noisy on a very young position, don't act on it yet.
            nextAction = "CONTINUE_SIP";
            nextActionReason = String.format("Only %d days held — XIRR is too noisy this early to act on; keep contributing on schedule.", daysHeld);
            positives.add("Recently started — too early to judge performance");
        } else if (pnlPercent >= 20 && !isLongTerm) {
            // The exact scenario the user described: strong short-term gain, tax-aware
            // decision instead of a flat "Sell".
            nextAction = "PARTIAL_PROFIT_BOOKING";
            long daysToLtcg = daysToLtcgFromBuy;
            nextActionReason = String.format("Up %.1f%% but still short-term (%d days held, %d to go for LTCG) — book part of the gain now and let the rest season into long-term tax treatment.", pnlPercent, daysHeld, daysToLtcg);
            positives.add(String.format("Up %.1f%% — strong short-term gain", pnlPercent));
            risks.add(String.format("Still short-term (%d days held) — STCG applies if redeemed now", daysHeld));
            factors.add(AnalystAssessment.Factor.builder().name("Tax timing").category("HOLDING").weightPct(0)
                .note(String.format("%d more days to reach LTCG treatment", daysToLtcg)).build());
        } else if ((!Double.isNaN(xirr) && xirr < 0) || pnlPercent < -8) {
            nextAction = "SWITCH_FUND";
            long displayDays = daysHeld == Long.MAX_VALUE ? 0 : daysHeld;
            nextActionReason = (!Double.isNaN(xirr) && xirr < 0)
                ? String.format("XIRR of %.1f%% over %d days held is negative/weak — this fund isn't compounding; a switch is worth considering.", xirr, displayDays)
                : String.format("Down %.1f%% overall — underperforming enough to reconsider this fund.", pnlPercent);
            risks.add(String.format("Underperforming (%s)", !Double.isNaN(xirr) ? String.format("XIRR %.1f%%", xirr) : String.format("%.1f%% return", pnlPercent)));
        } else if (!Double.isNaN(xirr) && xirr >= 15 && isLongTerm) {
            nextAction = "INCREASE_SIP";
            nextActionReason = String.format("XIRR of %.1f%% over %d days held (long-term) is strong compounding — worth increasing the monthly contribution.", xirr, daysHeld == Long.MAX_VALUE ? 0 : daysHeld);
            positives.add(String.format("Strong compounding (XIRR %.1f%%) over a long holding period", xirr));
        } else if (!Double.isNaN(xirr) && xirr >= 8) {
            nextAction = "CONTINUE_SIP";
            nextActionReason = String.format("XIRR of %.1f%% is on track — no reason to change the SIP amount either way.", xirr);
            positives.add(String.format("On track (XIRR %.1f%%)", xirr));
        } else {
            nextAction = "HOLD";
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

        // Benchmark over the SAME period: Nifty 50's annualised price return from the buy date,
        // compared with the fund's XIRR. The old comparison set lifetime P&L% against the Nifty
        // move over the last ~200 sessions — two different periods. With several SIP dates the
        // first-buy date makes this approximate, and the Nifty price index excludes dividends
        // (~1–1.5%/yr); both are stated.
        BenchmarkReturn nifty = req.getBuyDate() != null ? niftyReturnSince(req.getBuyDate()) : null;
        if (nifty != null) {
            boolean annualised = nifty.years >= 1 && !Double.isNaN(xirr);
            double fund = annualised ? xirr : pnlPercent;
            double bench = annualised ? nifty.annualisedPct : nifty.totalPct;
            double diff = fund - bench;
            String what = annualised ? "annualised" : "total";
            factors.add(AnalystAssessment.Factor.builder().name("Benchmark").category("BENCHMARK").weightPct(0)
                .reading(String.format("%+.1f pts vs Nifty 50", diff))
                .note(String.format("Fund %s %.1f%% vs Nifty 50 price index %s %.1f%% since %s (%s to %s). Nifty excludes dividends; SIP buys after the first make this approximate.",
                    annualised ? "XIRR" : "return", fund, what, bench, req.getBuyDate(), nifty.from, nifty.to)).build());
            if (diff >= 2) positives.add(String.format("Ahead of Nifty 50 by %.1f pts (%s, same period)", diff, what));
            else if (diff <= -2) risks.add(String.format("Behind Nifty 50 by %.1f pts (%s, same period)", Math.abs(diff), what));
        }

        String taxImpact = taxImpactText(current.subtract(invested), daysToLtcgFromBuy, isLongTerm);

        String basis = String.format(
            "Rule-of-thumb MF assessment (fixed thresholds: 90 days minimum, +20%% short-term gain, −8%%/negative XIRR, XIRR 8%%/15%% — heuristics, not backtested): %d days held (%s), XIRR %s, return %.1f%%%s. Not investment advice.",
            daysHeld == Long.MAX_VALUE ? 0 : daysHeld, isLongTerm ? "long-term" : "short-term",
            !Double.isNaN(xirr) ? String.format("%.1f%%", xirr) : "unavailable", pnlPercent,
            nifty != null ? String.format(", Nifty 50 since first buy %.1f%%", nifty.totalPct) : "");

        return AnalystAssessment.builder()
            .symbol(req.getSymbol()).displayName(req.getFundName() != null ? req.getFundName() : req.getSymbol())
            .price(current.doubleValue())
            // Funds are not rated: "BUY if in profit" was a label with nothing behind it.
            // nextAction is the answer for MFs, and it has no measured confidence.
            .rating("NOT_RATED")
            .conviction(null)
            .compositeScore(null)
            .factorBreakdown(factors)
            .positives(positives).risks(risks)
            .confidenceScore(null)
            .nextAction(nextAction)
            .nextActionReason(nextActionReason)
            .taxImpact(taxImpact)
            .basis(basis)
            .build();
    }

    private String taxImpactText(BigDecimal gain, long daysToLtcg, boolean isLongTerm) {
        if (gain.compareTo(BigDecimal.ZERO) <= 0) return "No tax impact — position is at a loss.";
        if (isLongTerm) {
            BigDecimal taxable = gain.subtract(LTCG_EXEMPTION).max(BigDecimal.ZERO);
            BigDecimal tax = taxable.multiply(LTCG_RATE).setScale(0, RoundingMode.HALF_UP);
            return String.format("LTCG: ₹%,.0f tax if redeemed today (%s%% over ₹1.25L/FY exemption).", tax, pct(LTCG_RATE));
        }
        BigDecimal tax = gain.multiply(STCG_RATE).setScale(0, RoundingMode.HALF_UP);
        return String.format("STCG: ₹%,.0f tax (%s%%) if redeemed today. LTCG treatment in %d more day%s.",
            tax, pct(STCG_RATE), daysToLtcg, daysToLtcg == 1 ? "" : "s");
    }

    private static String pct(BigDecimal rate) {
        return rate.movePointRight(2).stripTrailingZeros().toPlainString();
    }

    /**
     * One-year price return of the three benchmark indices, from the canonical daily series
     * (close nearest to 365 days ago → last close). Was "first to last of up to 200 stored bars",
     * i.e. about 9.5 months, labelled as a trailing return. Null when a year of history is missing.
     */
    public java.util.Map<String, Double> getIndexBenchmarks() {
        java.util.Map<String, Double> out = new java.util.LinkedHashMap<>();
        out.put("nifty50", oneYearReturn(NIFTY_SYMBOL));
        out.put("sensex", oneYearReturn("^BSESN"));
        out.put("bankNifty", oneYearReturn("^NSEBANK"));
        return out;
    }

    private Double oneYearReturn(String symbol) {
        BenchmarkReturn r = returnSince(symbol, LocalDate.now().minusYears(1));
        return r != null && r.years >= 0.95 ? Math.round(r.totalPct * 100) / 100.0 : null;
    }

    record BenchmarkReturn(LocalDate from, LocalDate to, double totalPct, double annualisedPct, double years) {}

    private BenchmarkReturn niftyReturnSince(LocalDate since) {
        return returnSince(NIFTY_SYMBOL, since);
    }

    /** Price return from the first close on or after {@code since} to the last close; null if history does not reach back. */
    private BenchmarkReturn returnSince(String symbol, LocalDate since) {
        try {
            com.marketai.market.quality.DailySeries s = marketDataService.getDailySeries(symbol, 2);
            if (s.status() == com.marketai.market.quality.SeriesStatus.INSUFFICIENT_DATA || s.bars().get(0).getDate().isAfter(since.plusDays(7))) return null;
            PriceHistory first = s.bars().stream().filter(b -> !b.getDate().isBefore(since)).findFirst().orElse(null);
            PriceHistory last = s.last();
            if (first == null || first == last) return null;
            double total = last.getClose().doubleValue() / first.getClose().doubleValue() - 1;
            double years = ChronoUnit.DAYS.between(first.getDate(), last.getDate()) / 365.25;
            double annual = years > 0 ? Math.pow(1 + total, 1 / years) - 1 : total;
            return new BenchmarkReturn(first.getDate(), last.getDate(), total * 100, annual * 100, years);
        } catch (Exception e) {
            log.debug("Benchmark return unavailable for {}: {}", symbol, e.getMessage());
            return null;
        }
    }
}
