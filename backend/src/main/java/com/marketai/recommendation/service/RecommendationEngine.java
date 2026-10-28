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
 *   - nextAction: the portfolio-context action (ACCUMULATE/CONTINUE/BOOK_PROFIT/AVOID/REVIEW/HOLD/REBALANCE),
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

    // Optional so the engine still works (and unit-tests) without the MF data stack.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.marketai.mf.service.MfPeerComparisonService mfPeers;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.marketai.mf.service.MfSchemeLinkService mfSchemeLink;

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
        if (symbol != null && symbol.toUpperCase().endsWith(".MF")) {
            // A fund has no exchange price feed; the stock engine would only look for one. Funds
            // are assessed by recommendMf (POST /api/recommendation/mf).
            return AnalystAssessment.builder()
                .symbol(symbol).displayName(displayName != null ? displayName : symbol)
                .rating("NOT_RATED")
                .factorBreakdown(new ArrayList<>()).positives(new ArrayList<>()).risks(new ArrayList<>())
                .dataQuality(DataQuality.INSUFFICIENT.wire()).barsAvailable(0)
                .nextAction("INSUFFICIENT_DATA")
                .nextActionReason("Mutual funds are assessed by the fund engine, from NAVs and holding details — not from exchange prices.")
                .build();
        }
        AnalystAssessment a = assessed(symbol, displayName);
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
            a.setNextActionReason(a.isHistoryPending()
                ? "Price history for " + symbol + " is still downloading behind other symbols. The analysis appears once it's in — usually within a minute."
                : String.format("Not enough valid price history for %s (%d daily bar(s)). No recommendation is shown rather than guessing one.",
                    symbol, a.getBarsAvailable() != null ? a.getBarsAvailable() : 0));
            return a;
        }
        if ("STALE_DATA".equals(a.getRating())) {
            a.setNextAction("STALE_DATA");
            a.setNextActionReason(marketDataService.isInactive(symbol)
                ? "No new prices for " + symbol + " for several sessions (newest bar " + a.getPriceDate()
                    + ") — it may be suspended or delisted. No action is suggested from old data."
                : "Price history for " + symbol + " is stale (newest bar " + a.getPriceDate()
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

    /**
     * How long a stock's assessment is reused. It costs a dozen database round trips plus quote
     * and news calls, and a holdings page asks for every held stock on each visit. Shared by every
     * screen, so the holdings badge and the AI Advisor still read the same assessment.
     */
    static final java.time.Duration ASSESSMENT_TTL = java.time.Duration.ofMinutes(10);

    private record Assessed(java.time.Instant at, AnalystAssessment a) {}

    private final java.util.concurrent.ConcurrentHashMap<String, Assessed> assessments = new java.util.concurrent.ConcurrentHashMap<>();

    /** On in the running app; an engine built directly (tests) assesses afresh every time. */
    @org.springframework.beans.factory.annotation.Value("${app.recommendation.reuse-assessments:true}")
    private boolean reuseAssessments;

    private static final com.fasterxml.jackson.databind.ObjectMapper COPIER = new com.fasterxml.jackson.databind.ObjectMapper()
        .findAndRegisterModules()
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * The assessment, from the last 10 minutes when there is one. Each caller gets its own copy:
     * the position's P&L and exposure are written onto it below. A result whose history is still
     * downloading, or that has no usable data, is not kept.
     */
    private AnalystAssessment assessed(String symbol, String displayName) {
        if (!reuseAssessments) return analystService.assess(symbol, displayName);
        String key = symbol + "|" + displayName;
        Assessed hit = assessments.get(key);
        if (hit != null && hit.at().plus(ASSESSMENT_TTL).isAfter(java.time.Instant.now())) {
            try {
                return COPIER.convertValue(hit.a(), AnalystAssessment.class);
            } catch (IllegalArgumentException e) {
                assessments.remove(key);
            }
        }
        AnalystAssessment a = analystService.assess(symbol, displayName);
        if (a != null && !a.isHistoryPending() && DataQuality.of(a.getDataQuality()).isUsable()) {
            try {
                assessments.put(key, new Assessed(java.time.Instant.now(), COPIER.convertValue(a, AnalystAssessment.class)));
            } catch (IllegalArgumentException e) {
                log.debug("Assessment for {} not kept: {}", symbol, e.getMessage());
            }
        }
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
        // No validated call: describe the position from the measured trend, so a holding isn't left
        // at a bare "no signal". The trend label showed no edge on forward returns, so it never
        // produces a trade call — no EXIT or AVOID without a validated SELL behind it. A downtrend
        // asks the holder to review; with no position the trend is context only.
        String trend = a.getFundamentals() != null ? a.getFundamentals().getTrend() : null;
        String trendLabel = trend != null ? trend.toLowerCase().replace('_', ' ') : "unknown";
        String basis = " Trend-based, not a backtested signal." + ruleNote;
        boolean readable = trend != null && (trend.contains("UPTREND") || trend.contains("DOWNTREND") || trend.equals("SIDEWAYS"));
        if (readable && !hasPosition) {
            return new NextActionDecision(AnalystService.NO_ACTIONABLE_SIGNAL, "No validated signal. Trend is " + trendLabel
                + " — context only, not a reason to buy or sell." + basis);
        }
        if (trend != null && trend.contains("DOWNTREND")) {
            String where = pnlPercent >= 0 ? String.format("up %.1f%%", pnlPercent) : String.format("down %.1f%%", Math.abs(pnlPercent));
            return new NextActionDecision("REVIEW", "Trend is " + trendLabel + " while you're " + where
                + " — re-check your reason for holding and where your stop-loss is." + basis);
        }
        if (trend != null && trend.contains("UPTREND")) {
            return new NextActionDecision("CONTINUE", "Trend is " + trendLabel + " — stay invested, no new action needed." + basis);
        }
        if (trend != null && trend.equals("SIDEWAYS")) {
            return new NextActionDecision("HOLD", "Trend is sideways — nothing about this stock warrants action today." + basis);
        }
        return new NextActionDecision(AnalystService.NO_ACTIONABLE_SIGNAL, String.format(
            "No validated signal and no readable trend — nothing in the verified data justifies a trade on its own.%s %s", ruleNote, record));
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
        // A fund worth more than was put in cannot have a negative annualised return, and one worth
        // less cannot have a positive one. A contradiction means the XIRR was built from an incomplete
        // ledger — don't act on it (it would turn a profitable fund into "Switch Fund").
        boolean xirrContradictsPnl = !Double.isNaN(xirr) && invested.signum() > 0
            && ((pnlPercent > 1 && xirr < 0) || (pnlPercent < -1 && xirr > 0));
        if (xirrContradictsPnl) xirr = Double.NaN;
        long daysHeld = req.getBuyDate() != null ? ChronoUnit.DAYS.between(req.getBuyDate(), LocalDate.now()) : Long.MAX_VALUE;
        // An unknown purchase date is unknown — not "long-term", and not "bought today". Imports
        // from a holdings summary (a CAS summary, a broker holdings file) carry no purchase dates.
        boolean dateKnown = req.getBuyDate() != null;
        boolean isLongTerm = dateKnown
            && com.marketai.tax.lot.CapitalGainsRates.isLongTerm(req.getBuyDate(), LocalDate.now());
        String sipStatus = req.getSipStatus() != null ? req.getSipStatus() : "NONE";
        boolean sip = "ACTIVE".equals(sipStatus) || "INFERRED".equals(sipStatus);
        long daysToLtcgFromBuy = com.marketai.tax.lot.CapitalGainsRates.daysToLongTerm(req.getBuyDate(), LocalDate.now());

        List<String> positives = new ArrayList<>();
        List<String> risks = new ArrayList<>();
        List<AnalystAssessment.Factor> factors = new ArrayList<>();
        String nextAction;
        String nextActionReason;

        factors.add(AnalystAssessment.Factor.builder().name("Holding period").category("HOLDING").weightPct(0)
            .note(dateKnown
                ? String.format("%d days held (%s)", daysHeld, isLongTerm ? "long-term" : "short-term")
                : "Purchase date not on record — holding period and tax treatment unknown").build());
        if (xirrContradictsPnl) {
            factors.add(AnalystAssessment.Factor.builder().name("XIRR").category("HOLDING").weightPct(0)
                .note("The recorded XIRR contradicts the fund's gain/loss (transaction history looks incomplete) — ignored; judged on return alone.").build());
        }
        factors.add(AnalystAssessment.Factor.builder().name("Investment mode").category("HOLDING").weightPct(0)
            .note(switch (sipStatus) {
                case "ACTIVE" -> "SIP" + (req.getSipAmount() != null ? String.format(" of ₹%,.0f", req.getSipAmount()) : "") + " (recorded)";
                case "PAUSED" -> "SIP paused (recorded)";
                case "INFERRED" -> "SIP (monthly purchases in the ledger)";
                default -> "Lump sum — no SIP recorded for this fund";
            }).build());

        // Category standing — the question that actually decides "keep or switch": is this fund
        // beating the funds you could hold instead? A loss the whole category shares is the
        // market, not the fund.
        PeerView peer = peerView(req, factors, positives, risks);
        TrackRecord track = peer != null ? peer.track() : null;

        if (dateKnown && daysHeld < 90) {
            // Too early — XIRR is noisy on a very young position, don't act on it yet.
            if (sip) {
                nextAction = "CONTINUE_SIP";
                nextActionReason = String.format("Only %d days held — XIRR is too noisy this early to act on; keep contributing on schedule.", daysHeld);
            } else {
                nextAction = "HOLD";
                nextActionReason = String.format("Bought only %d days ago — too early to judge the fund; nothing to do yet.", daysHeld);
            }
            positives.add("Recently started — too early to judge performance");
        } else if (dateKnown && pnlPercent >= 20 && !isLongTerm) {
            // Strong short-term gain: a tax-aware partial booking instead of a flat "Sell".
            nextAction = "PARTIAL_PROFIT_BOOKING";
            long daysToLtcg = daysToLtcgFromBuy;
            nextActionReason = String.format("Up %.1f%% but still short-term (%d days held, %d to go for LTCG) — book part of the gain now and let the rest season into long-term tax treatment.", pnlPercent, daysHeld, daysToLtcg);
            positives.add(String.format("Up %.1f%% — strong short-term gain", pnlPercent));
            risks.add(String.format("Still short-term (%d days held) — STCG applies if redeemed now", daysHeld));
            factors.add(AnalystAssessment.Factor.builder().name("Tax timing").category("HOLDING").weightPct(0)
                .note(String.format("%d more days to reach LTCG treatment", daysToLtcg)).build());
        } else if (peer != null && peer.laggard()) {
            String instead = peer.alternatives.isEmpty() ? "" : " Stronger funds in the same category: " + peer.alternatives + ".";
            if (sip) {
                nextAction = "REDIRECT_SIP";
                nextActionReason = "It " + peer.summary + " — consistently behind its category. Send future SIP instalments to a stronger "
                    + "fund in the same category and keep the units you hold (no exit load or tax on those)." + instead;
            } else {
                nextAction = "SWITCH_FUND";
                nextActionReason = "It " + peer.summary + " — consistently behind its category. A switch is worth considering; it is a "
                    + "redemption plus a purchase, so check the exit load and tax first." + instead;
            }
        } else if (peer != null && peer.leader() && ((!Double.isNaN(xirr) && xirr < 0) || pnlPercent < -8)) {
            nextAction = sip ? "CONTINUE_SIP" : "HOLD";
            nextActionReason = String.format("Down %.1f%%, but it %s — the fall is the market, not the fund. Switching would sell low into a weaker fund.%s",
                pnlPercent, peer.summary, sip ? " Keep the SIP running; instalments now buy more units." : "");
        } else if (track != null && track.strong() && ((!Double.isNaN(xirr) && xirr < 0) || pnlPercent < -8)) {
            // Your position is down, but the fund itself has done its job: the fall is the market.
            nextAction = sip ? "CONTINUE_SIP" : "HOLD";
            nextActionReason = String.format("Your position is down %.1f%%, but the fund has returned %s — the fall is the market, "
                + "not the fund. Selling now would book the loss.%s", pnlPercent, track.describe(),
                sip ? " Keep the SIP running; instalments now buy more units." : "");
        } else if ((!Double.isNaN(xirr) && xirr < 0) || pnlPercent < -8) {
            String weak = (!Double.isNaN(xirr) && xirr < 0)
                ? String.format("XIRR of %.1f%%%s is negative", xirr, dateKnown ? String.format(" over %d days held", daysHeld) : "")
                : String.format("Down %.1f%% overall", pnlPercent);
            // A switch needs evidence the fund is the problem — a weaker record than its category.
            // Without that ranking, a loss is a reason to look closer, not to sell.
            if (sip && track != null && track.weak()) {
                // Rotate the SIP, not the money already in: new instalments go to a stronger fund,
                // and the units held stay put, so no loss is booked and no exit load or tax is paid.
                nextAction = "REDIRECT_SIP";
                nextActionReason = weak + ", and the fund has returned " + track.describe() + " — redirect future SIP "
                    + "instalments to a stronger fund in the same category, and keep the units you already hold rather than selling them at a loss.";
            } else {
                nextAction = "REVIEW";
                nextActionReason = weak + (track != null && !track.describe().isEmpty() ? "; the fund has returned " + track.describe() : "")
                    + ". " + (track != null && track.weak()
                        ? "It has lagged the market for years, not just recently — compare it with its category before deciding to switch."
                        : "Its category ranking is not available yet, so there is no evidence the fund itself is the problem — review it, don't sell on the loss alone.");
            }
            risks.add(String.format("Underperforming (%s)", !Double.isNaN(xirr) ? String.format("XIRR %.1f%%", xirr) : String.format("%.1f%% return", pnlPercent)));
        } else if (!Double.isNaN(xirr) && xirr >= 15 && isLongTerm) {
            if (sip) {
                nextAction = "INCREASE_SIP";
                nextActionReason = String.format("XIRR of %.1f%% over %d days held (long-term) is strong compounding — worth increasing the monthly contribution.", xirr, daysHeld);
            } else {
                nextAction = "TOP_UP";
                nextActionReason = String.format("XIRR of %.1f%% over %d days held (long-term) is strong compounding — a further lump sum (or starting a SIP) is reasonable, staggered rather than all at once.", xirr, daysHeld);
            }
            positives.add(String.format("Strong compounding (XIRR %.1f%%) over a long holding period", xirr));
        } else if ((track != null && track.strong()) || (peer != null && peer.leader())) {
            // A fund that keeps beating the market (or its category) is where new money belongs.
            String why = peer != null && peer.leader() ? "it " + peer.summary() : "it has returned " + track.describe();
            if (sip) {
                nextAction = "INCREASE_SIP";
                nextActionReason = "A strong fund: " + why + ". Increasing the monthly SIP is reasonable if it fits your plan.";
            } else {
                nextAction = "TOP_UP";
                nextActionReason = "A strong fund: " + why + ". Adding more here is reasonable — ideally in instalments (a SIP or STP) rather than all at once.";
            }
        } else if (track != null && track.weak()) {
            nextAction = "REVIEW";
            nextActionReason = "The fund has returned " + track.describe() + " — behind the market over 3 years and the last year. "
                + "Nothing urgent, but compare it with its category before adding more.";
        } else if (!Double.isNaN(xirr) && xirr >= 8) {
            nextAction = sip ? "CONTINUE_SIP" : "HOLD";
            nextActionReason = sip
                ? String.format("XIRR of %.1f%% is on track — no reason to change the SIP amount either way.", xirr)
                : String.format("XIRR of %.1f%% is on track — hold the lump sum; nothing needs changing.", xirr);
            positives.add(String.format("On track (XIRR %.1f%%)", xirr));
        } else {
            nextAction = sip ? "CONTINUE_SIP" : "HOLD";
            String record = track != null && !track.describe().isEmpty() ? "The fund has returned " + track.describe() + "; your position is "
                : "Your position is ";
            nextActionReason = record + String.format("%s%.1f%% — nothing here warrants a change today", pnlPercent >= 0 ? "up " : "down ", Math.abs(pnlPercent))
                + (sip ? "; keep the SIP running." : ".");
        }
        if ("PAUSED".equals(sipStatus) && ("HOLD".equals(nextAction) || "TOP_UP".equals(nextAction))) {
            nextActionReason += " Your SIP in this fund is paused — resume it if you still want to keep adding.";
        }

        // Concentration check — flagged as a risk alongside whatever the primary action is,
        // not as a replacement for it.
        if (req.getTotalMfPortfolioValue() != null && req.getTotalMfPortfolioValue().compareTo(BigDecimal.ZERO) > 0
                && current.compareTo(BigDecimal.ZERO) > 0) {
            double concentration = current.divide(req.getTotalMfPortfolioValue(), 4, RoundingMode.HALF_UP).doubleValue() * 100;
            if (concentration > 25) {
                risks.add(String.format("%.0f%% of your mutual fund portfolio is in this one fund — concentration risk", concentration));
                if (java.util.Set.of("HOLD", "CONTINUE_SIP", "INCREASE_SIP", "TOP_UP").contains(nextAction)) {
                    if (sip) {
                        nextAction = "REDIRECT_SIP";
                        nextActionReason = String.format("%.0f%% of your MF portfolio sits in this one fund — send future SIP instalments to a different fund instead of adding more here.", concentration);
                    } else {
                        nextAction = "REBALANCE";
                        nextActionReason = String.format("%.0f%% of your MF portfolio sits in this one fund — that concentration risk now outweighs the otherwise fine performance.", concentration);
                    }
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

        String taxImpact = dateKnown
            ? taxImpactText(current.subtract(invested), daysToLtcgFromBuy, isLongTerm)
            : current.subtract(invested).signum() <= 0 ? "No tax impact — position is at a loss."
            : "Tax depends on when each unit was bought, and the purchase dates are not on record, so STCG vs LTCG "
              + "cannot be worked out. Importing a detailed CAS (with transactions) fills this in.";

        String basis = String.format(
            "Rule-of-thumb MF assessment (fixed thresholds: 90 days minimum, +20%% short-term gain, −8%%/negative XIRR, XIRR 8%%/15%% — heuristics, not backtested): %d days held (%s), XIRR %s, return %.1f%%%s. Not investment advice.",
            daysHeld == Long.MAX_VALUE ? 0 : daysHeld, !dateKnown ? "purchase date unknown" : isLongTerm ? "long-term" : "short-term",
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

    /** The category verdict used by {@link #recommendMf}. */
    private record PeerView(boolean laggard, boolean leader, String summary, String alternatives, TrackRecord track) {}

    /**
     * The fund's own record from its NAV history, which needs no purchase dates: trailing
     * returns, and how far ahead of or behind the Nifty 50 it has been over the same windows
     * (equity funds only — a debt or gold fund is not meant to track the Nifty).
     */
    record TrackRecord(String category, boolean equity, Double r1, Double r3, Double r5,
                       Double vsNifty1, Double vsNifty3, Double volatility, Double maxDrawdown) {
        /** Ahead of the Nifty over 3 years (or 1 year for a younger fund). */
        boolean strong() {
            return equity && (vsNifty3 != null ? vsNifty3 >= 2 : vsNifty1 != null && vsNifty1 >= 2);
        }
        /** Behind the Nifty over 3 years and still behind over the last year. */
        boolean weak() {
            return equity && vsNifty3 != null && vsNifty3 <= -3 && (vsNifty1 == null || vsNifty1 < 0);
        }
        /** Neither ahead nor clearly behind, or not comparable. */
        String describe() {
            StringBuilder b = new StringBuilder();
            if (r3 != null) b.append(String.format("%.1f%% a year over 3 years", r3));
            else if (r1 != null) b.append(String.format("%.1f%% over the last year", r1));
            if (equity && (vsNifty3 != null || vsNifty1 != null)) {
                double d = vsNifty3 != null ? vsNifty3 : vsNifty1;
                b.append(String.format(" (%s the Nifty 50 by %.1f pts)", d >= 0 ? "ahead of" : "behind", Math.abs(d)));
            }
            return b.toString();
        }
    }

    private static Double d(BigDecimal v) { return v != null ? v.doubleValue() : null; }

    /**
     * Ranks the fund in its SEBI category (3Y CAGR, falling back to 1Y for young funds) and against
     * Nifty 50, adding what it found to the factors. Null when the fund is unlinked or peers are
     * still loading — the caller then falls back to the fund's own return.
     */
    private PeerView peerView(MfRecommendationRequest req, List<AnalystAssessment.Factor> factors,
                              List<String> positives, List<String> risks) {
        if (mfPeers == null) return null;
        String code = req.getSchemeCode();
        if ((code == null || code.isBlank()) && mfSchemeLink != null && req.getFundName() != null) {
            code = mfSchemeLink.resolveSchemeCodeByFundName(req.getFundName()).orElse(null);
        }
        if (code == null) {
            factors.add(AnalystAssessment.Factor.builder().name("Category comparison").category("BENCHMARK").weightPct(0)
                .note("Fund is not linked to an AMFI scheme, so it can't be compared with its category.").build());
            return null;
        }
        java.util.Optional<com.marketai.mf.service.MfPeerComparisonService.Comparison> found;
        try { found = mfPeers.compare(code); } catch (Exception e) {
            log.warn("MF peer comparison failed for {}: {}", code, e.getMessage());
            return null;
        }
        if (found.isEmpty()) return null;
        var c = found.get();

        var bucket = com.marketai.amfi.dto.MfCategoryBucket.from(c.category());
        boolean equity = bucket != null && !java.util.Set.of(com.marketai.amfi.dto.MfCategoryBucket.DEBT,
            com.marketai.amfi.dto.MfCategoryBucket.LIQUID, com.marketai.amfi.dto.MfCategoryBucket.GOLD_SILVER,
            com.marketai.amfi.dto.MfCategoryBucket.INTERNATIONAL, com.marketai.amfi.dto.MfCategoryBucket.OTHER).contains(bucket);
        Double vs1 = null, vs3 = null;
        var m = c.fund();
        if (m != null && m.getObservationCount() > 0) {
            factors.add(AnalystAssessment.Factor.builder().name("Fund track record").category("PERFORMANCE").weightPct(0)
                .reading(m.getReturn3YCagr() != null ? String.format("%.1f%% a year (3Y)", m.getReturn3YCagr().doubleValue())
                    : m.getReturn1Y() != null ? String.format("%.1f%% (1Y)", m.getReturn1Y().doubleValue()) : null)
                .note(String.format("From the fund's NAV history%s%s%s%s.",
                    m.getReturn1Y() != null ? String.format(": 1Y %.1f%%", m.getReturn1Y().doubleValue()) : "",
                    m.getReturn3YCagr() != null ? String.format(", 3Y %.1f%% a year", m.getReturn3YCagr().doubleValue()) : "",
                    m.getReturn5YCagr() != null ? String.format(", 5Y %.1f%% a year", m.getReturn5YCagr().doubleValue()) : "",
                    m.getMaxDrawdownPercent() != null ? String.format("; worst fall %.1f%%", m.getMaxDrawdownPercent().abs().doubleValue()) : ""))
                .build());
            if (m.getAnnualisedVolatility() != null) {
                factors.add(AnalystAssessment.Factor.builder().name("Volatility").category("RISK").weightPct(0)
                    .reading(String.format("%.1f%% a year", m.getAnnualisedVolatility().doubleValue()))
                    .note("How much the NAV swings — higher means a bumpier ride, not a worse fund.").build());
            }
        }

        // Fund vs Nifty 50 over the same trailing windows, from the fund's own NAV history.
        // Only for equity funds: a debt, liquid or gold fund is not meant to beat the Nifty.
        for (String w : equity ? List.of("1Y", "3Y", "5Y") : List.<String>of()) {
            BigDecimal f = switch (w) { case "1Y" -> c.fund().getReturn1Y(); case "3Y" -> c.fund().getReturn3YCagr(); default -> c.fund().getReturn5YCagr(); };
            if (f == null) continue;
            int years = Integer.parseInt(w.substring(0, 1));
            BenchmarkReturn n = returnSince(NIFTY_SYMBOL, LocalDate.now().minusYears(years));
            if (n == null || n.years < years - 0.05) continue;
            double bench = years == 1 ? n.totalPct : n.annualisedPct;
            double diff = f.doubleValue() - bench;
            if (years == 1) vs1 = diff;
            if (years == 3) vs3 = diff;
            factors.add(AnalystAssessment.Factor.builder().name("vs Nifty 50 (" + w + ")").category("BENCHMARK").weightPct(0)
                .reading(String.format("%+.1f pts", diff))
                .note(String.format("Fund %.1f%% vs Nifty 50 %.1f%%%s (NAV-based; Nifty price index excludes dividends).",
                    f.doubleValue(), bench, years == 1 ? "" : " a year")).build());
        }

        TrackRecord track = m == null || m.getObservationCount() == 0 ? null : new TrackRecord(c.category(), equity,
            d(m.getReturn1Y()), d(m.getReturn3YCagr()), d(m.getReturn5YCagr()), vs1, vs3,
            d(m.getAnnualisedVolatility()), m.getMaxDrawdownPercent() != null ? m.getMaxDrawdownPercent().abs().doubleValue() : null);
        if (track != null && equity) {
            if (track.strong()) positives.add("Consistently ahead of the Nifty 50 — " + track.describe());
            else if (track.weak()) risks.add("Behind the Nifty 50 over 3 years and the last year — " + track.describe());
        }

        if (c.ranks().isEmpty()) {
            factors.add(AnalystAssessment.Factor.builder().name("Category comparison").category("BENCHMARK").weightPct(0)
                .note(c.peerDataLoading()
                    ? String.format("Loading NAV history for %d %s peers in %s — the ranking appears on the next refresh.", c.peersInCategory(), c.plan(), c.category())
                    : String.format("Not enough peers with history in %s to rank against.", c.category())).build());
            return new PeerView(false, false, null, "", track);
        }
        for (var r : c.ranks()) {
            factors.add(AnalystAssessment.Factor.builder().name("Category rank (" + r.window() + ")").category("BENCHMARK").weightPct(0)
                .reading(String.format("%d of %d", r.rank(), r.ranked()))
                .note(String.format("%s: %.1f%% vs category median %.1f%% among %s %s growth plans.",
                    r.quartileLabel(), r.fund().doubleValue(), r.categoryMedian().doubleValue(), c.plan(), c.category())).build());
        }

        var primary = c.rank("3Y").orElse(c.rank("1Y").orElse(c.ranks().get(0)));
        var oneYear = c.rank("1Y").orElse(null);
        int p = primary.percentileFromTop();
        // Laggard: bottom quartile on the long window, and not rescued by a recent turnaround.
        boolean laggard = p > 75 && (oneYear == null || oneYear == primary || oneYear.percentileFromTop() > 50);
        boolean leader = p <= 50;
        String summary = String.format("ranks %d of %d in its category on %s returns (%s; %.1f%% vs median %.1f%%)",
            primary.rank(), primary.ranked(), primary.window(), primary.quartileLabel(),
            primary.fund().doubleValue(), primary.categoryMedian().doubleValue());
        if (leader) positives.add("Category " + summary.replaceFirst("^ranks", "rank"));
        if (p > 75) risks.add("Category " + summary.replaceFirst("^ranks", "rank"));

        String alternatives = String.join("; ", c.leaders().stream()
            .map(l -> String.format("%s (%s %.1f%%)", l.name(),
                l.m().getReturn3YCagr() != null ? "3Y" : "1Y",
                (l.m().getReturn3YCagr() != null ? l.m().getReturn3YCagr() : l.m().getReturn1Y()).doubleValue()))
            .toList());
        return new PeerView(laggard, leader, summary, alternatives, track);
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
