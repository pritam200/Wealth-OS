package com.marketai.actions.service;

import com.marketai.actions.dto.TodaysActionsResponse;
import com.marketai.actions.dto.TodaysActionsResponse.*;
import com.marketai.analyst.dto.AnalystAssessment;
import com.marketai.portfolio.entity.Holding;
import com.marketai.recommendation.dto.MfRecommendationRequest;
import com.marketai.recommendation.dto.PortfolioContext;
import com.marketai.recommendation.service.PortfolioContextService;
import com.marketai.recommendation.service.RecommendationEngine;
import com.marketai.technical.dto.TechnicalAnalysisDto;
import com.marketai.technical.service.TechnicalIndicatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Answers "what should I do with my money today?" by running every held security through the
 * SAME {@link RecommendationEngine} every other tab uses, then bucketing the results — it adds
 * no new decision logic of its own, only aggregation and reinvestment-plan math for the
 * BOOK_PROFIT bucket. This is deliberate: a second scoring path here would reopen exactly the
 * "same stock, different advice" bug this session already fixed once.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TodaysActionsService {

    private static final String NIFTY_SYMBOL = "^NSEI";

    // Tiered partial-profit-booking percentages, keyed off unrealised gain — larger gains
    // warrant trimming a larger slice, but never a full exit on a profit signal alone (that's
    // what EXIT/rating=SELL-in-loss is for). Same principle the MF engine already applies at
    // its own 20% threshold; stocks get an extra, steeper tier since equity gains compound
    // concentration risk faster than a single MF holding does.
    private static final double PROFIT_TIER_HIGH = 50.0;   // >=50% gain → book 30%
    private static final double PROFIT_TIER_MID  = 30.0;   // >=30% gain → book 20%
    private static final double PROFIT_TIER_LOW  = 20.0;   // >=20% gain → book 15%
    private static final double BOOK_PCT_HIGH = 30.0, BOOK_PCT_MID = 20.0, BOOK_PCT_LOW = 15.0, BOOK_PCT_DEFAULT = 10.0;

    private static final double SINGLE_STOCK_GUIDELINE_PCT = 25.0; // matches PortfolioContextService

    private final PortfolioContextService portfolioContextService;
    private final RecommendationEngine recommendationEngine;
    private final TechnicalIndicatorService technicalIndicatorService;

    @Transactional(readOnly = true)
    public TodaysActionsResponse build(Long userId) {
        PortfolioContext ctx = portfolioContextService.build(userId);
        List<Holding> holdings = portfolioContextService.getAllHoldings(userId);

        List<BuyAction> buy = new ArrayList<>();
        List<SellReduceAction> sellReduce = new ArrayList<>();
        List<BookProfitAction> bookProfit = new ArrayList<>();
        List<HoldAction> hold = new ArrayList<>();
        List<WatchAction> watch = new ArrayList<>();
        List<NotAnalysed> notAnalysed = new ArrayList<>();

        for (Holding h : holdings) {
            BigDecimal cur = h.getCurrentValue();
            if (cur == null || cur.compareTo(BigDecimal.ZERO) <= 0) continue;

            boolean isMf = PortfolioContextService.isMf(h);
            AnalystAssessment a = isMf ? assessMf(h, ctx) : assessStock(h, ctx);
            String symbol = h.getSymbol();
            String name = h.getName() != null ? h.getName() : symbol;
            String assetType = isMf ? "MF" : "STOCK";
            Double pnlPercent = h.getPnlPercent() != null ? h.getPnlPercent().doubleValue() : null;

            if (a == null || "INSUFFICIENT_DATA".equals(a.getNextAction())) {
                notAnalysed.add(NotAnalysed.builder()
                    .symbol(symbol).name(name).assetType(assetType)
                    .reason(a != null && a.getNextActionReason() != null ? a.getNextActionReason() : "Recommendation engine returned no result.")
                    .build());
                continue;
            }

            String action = a.getNextAction();
            switch (action) {
                case "ACCUMULATE":
                case "INCREASE_SIP": {
                    Double pctOfEquity = pctOfEquity(cur, isMf, ctx);
                    BigDecimal equityBase = isMf ? ctx.getMfValue() : ctx.getStocksValue();
                    BigDecimal maxAdd = null;
                    if (equityBase != null && equityBase.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal ceiling = equityBase.multiply(BigDecimal.valueOf(SINGLE_STOCK_GUIDELINE_PCT / 100.0));
                        maxAdd = ceiling.subtract(cur).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
                    }
                    buy.add(BuyAction.builder()
                        .symbol(symbol).name(name).assetType(assetType)
                        .currentValue(cur).currentPercentOfEquity(pctOfEquity)
                        .maxAddWithoutBreachingGuideline(maxAdd)
                        .why(a.getNextActionReason())
                        .risk(topRisk(a))
                        .confidence(a.getConfidenceScore())
                        .build());
                    break;
                }
                case "BOOK_PROFIT":
                case "PARTIAL_PROFIT_BOOKING": {
                    bookProfit.add(buildBookProfit(h, a, symbol, name, assetType, cur, pnlPercent));
                    break;
                }
                case "EXIT":
                case "SWITCH_FUND":
                case "FULL_REDEMPTION": {
                    sellReduce.add(SellReduceAction.builder()
                        .symbol(symbol).name(name).assetType(assetType)
                        .currentValue(cur).pnlPercent(pnlPercent)
                        .action("EXIT".equals(action) ? "FULL_EXIT" : "SWITCH_FUND".equals(action) ? "SWITCH" : "FULL_EXIT")
                        .why(a.getNextActionReason())
                        .riskReward(topRisk(a))
                        .taxImpact(a.getTaxImpact())
                        .build());
                    break;
                }
                case "CONTINUE":
                case "CONTINUE_SIP":
                case "HOLD": {
                    hold.add(HoldAction.builder()
                        .symbol(symbol).name(name).assetType(assetType)
                        .currentValue(cur).pnlPercent(pnlPercent)
                        .why(a.getNextActionReason())
                        .build());
                    break;
                }
                case "REVIEW":
                case "PAUSE_SIP":
                case "REBALANCE": {
                    watch.add(WatchAction.builder()
                        .symbol(symbol).name(name).assetType(assetType)
                        .condition(watchCondition(action, a))
                        .why(a.getNextActionReason())
                        .build());
                    break;
                }
                default: {
                    log.warn("Today's Actions: unmapped nextAction '{}' for {} — routing to Watch so it isn't silently dropped.", action, symbol);
                    watch.add(WatchAction.builder()
                        .symbol(symbol).name(name).assetType(assetType)
                        .condition("Unrecognised action '" + action + "' — review manually.")
                        .why(a.getNextActionReason())
                        .build());
                }
            }
        }

        // Portfolio-level flags (sector/single-stock/allocation/leverage) aren't tied to any
        // one security's engine verdict — surface them here so a real concentration risk is
        // visible even when no individual holding's own rating happened to flag it.
        if (ctx.getConcentrationFlags() != null) {
            for (PortfolioContext.Flag f : ctx.getConcentrationFlags()) {
                watch.add(WatchAction.builder()
                    .symbol(null).name(f.getLabel()).assetType("PORTFOLIO")
                    .condition(f.getSeverity() + " — " + f.getPercent() + "%")
                    .why(f.getMessage())
                    .build());
            }
        }

        return TodaysActionsResponse.builder()
            .generatedAt(LocalDateTime.now())
            .scopeNote("Covers securities you already hold. New-buy ideas outside your portfolio are not surfaced here — sizing a rupee amount for a new purchase would require your available cash balance, which this app does not track, and any figure would otherwise be invented.")
            .portfolioContext(ctx)
            .buy(buy).sellReduce(sellReduce).bookProfit(bookProfit).hold(hold).watch(watch)
            .notAnalysed(notAnalysed)
            .build();
    }

    private AnalystAssessment assessStock(Holding h, PortfolioContext ctx) {
        try {
            Double pnlPercent = h.getPnlPercent() != null ? h.getPnlPercent().doubleValue() : null;
            Double holdingValue = h.getCurrentValue() != null ? h.getCurrentValue().doubleValue() : null;
            Double totalStockValue = ctx.getStocksValue() != null ? ctx.getStocksValue().doubleValue() : null;
            return recommendationEngine.recommend(h.getSymbol(), h.getName(), pnlPercent, holdingValue, totalStockValue);
        } catch (Exception e) {
            log.warn("Today's Actions: recommend() failed for stock {}: {}", h.getSymbol(), e.getMessage());
            return null;
        }
    }

    private AnalystAssessment assessMf(Holding h, PortfolioContext ctx) {
        try {
            MfRecommendationRequest req = new MfRecommendationRequest();
            req.setSymbol(h.getSymbol());
            req.setFundName(h.getName());
            req.setBuyDate(h.getBuyDate());
            req.setXirr(h.getXirr());
            req.setInvestedValue(h.getInvestedValue());
            req.setCurrentValue(h.getCurrentValue());
            req.setQuantity(h.getQuantity());
            req.setTotalMfPortfolioValue(ctx.getMfValue());
            return recommendationEngine.recommendMf(req);
        } catch (Exception e) {
            log.warn("Today's Actions: recommendMf() failed for {}: {}", h.getSymbol(), e.getMessage());
            return null;
        }
    }

    private Double pctOfEquity(BigDecimal value, boolean isMf, PortfolioContext ctx) {
        BigDecimal base = isMf ? ctx.getMfValue() : ctx.getStocksValue();
        if (base == null || base.compareTo(BigDecimal.ZERO) <= 0) return null;
        return Math.round(value.divide(base, 6, RoundingMode.HALF_UP).doubleValue() * 1000) / 10.0;
    }

    private String topRisk(AnalystAssessment a) {
        if (a.getRisks() != null && !a.getRisks().isEmpty()) return a.getRisks().get(0);
        return "See reasoning.";
    }

    private String watchCondition(String action, AnalystAssessment a) {
        switch (action) {
            case "REVIEW": return "Re-check if the trend confirms into a stronger downtrend, or set a stop-loss now.";
            case "PAUSE_SIP": return "Resume once the fund's XIRR/trend improves — see reasoning.";
            case "REBALANCE": return "Trim toward the 25% single-fund guideline on the next rebalance.";
            default: return a.getNextActionReason();
        }
    }

    private BookProfitAction buildBookProfit(Holding h, AnalystAssessment a, String symbol, String name,
                                              String assetType, BigDecimal currentValue, Double pnlPercent) {
        double pnl = pnlPercent != null ? pnlPercent : 0;
        double bookPct = pnl >= PROFIT_TIER_HIGH ? BOOK_PCT_HIGH
            : pnl >= PROFIT_TIER_MID ? BOOK_PCT_MID
            : pnl >= PROFIT_TIER_LOW ? BOOK_PCT_LOW
            : BOOK_PCT_DEFAULT;

        BigDecimal invested = h.getInvestedValue();
        BigDecimal profitAmount = invested != null ? currentValue.subtract(invested).max(BigDecimal.ZERO) : null;
        BigDecimal bookAmount = currentValue.multiply(BigDecimal.valueOf(bookPct / 100.0)).setScale(2, RoundingMode.HALF_UP);

        return BookProfitAction.builder()
            .symbol(symbol).name(name).assetType(assetType)
            .currentValue(currentValue).pnlPercent(pnlPercent)
            .currentProfitAmount(profitAmount != null ? profitAmount.setScale(2, RoundingMode.HALF_UP) : null)
            .suggestedBookPercent(bookPct)
            .suggestedBookAmount(bookAmount)
            .why(a.getNextActionReason())
            .taxImpact(a.getTaxImpact())
            .reinvestmentPlan(buildReinvestmentPlan(bookAmount))
            .build();
    }

    /**
     * Same staged-deployment principle as {@code RedemptionService.getDeploymentPlan} (20%
     * now / 30% after a correction / 50% via monthly SIP), computed ahead of an actual
     * redemption rather than after one. The "after a correction" trigger is Nifty 50's own
     * ATR-derived expected move — a real, current number — never a guessed market bottom.
     */
    private ReinvestmentPlan buildReinvestmentPlan(BigDecimal totalToRedeploy) {
        BigDecimal now = totalToRedeploy.multiply(BigDecimal.valueOf(0.20)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal afterCorrection = totalToRedeploy.multiply(BigDecimal.valueOf(0.30)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal viaSip = totalToRedeploy.subtract(now).subtract(afterCorrection);

        String trigger = "Market data unavailable — no specific pullback level could be computed today.";
        try {
            TechnicalAnalysisDto ta = technicalIndicatorService.analyse(NIFTY_SYMBOL);
            if (ta.getPrice() != null && ta.getAtr() != null) {
                double price = ta.getPrice().doubleValue();
                double sigma = ta.getAtr().doubleValue() * Math.sqrt(21); // ~1-month expected move
                double level = price - sigma;
                trigger = String.format("Nifty 50 below %.0f (a %.1f%% pullback from today's %.0f)", level, sigma / price * 100, price);
            }
        } catch (Exception e) {
            log.debug("Could not compute reinvestment trigger: {}", e.getMessage());
        }

        List<ReinvestmentPlan.Tranche> tranches = new ArrayList<>();
        tranches.add(ReinvestmentPlan.Tranche.builder().label("Invest now").amount(now).percentOfTotal(20.0).condition("Immediate").build());
        tranches.add(ReinvestmentPlan.Tranche.builder().label("After a correction").amount(afterCorrection).percentOfTotal(30.0).condition(trigger).build());
        tranches.add(ReinvestmentPlan.Tranche.builder().label("Monthly SIP over 6 months").amount(viaSip).percentOfTotal(50.0)
            .condition("~" + viaSip.divide(BigDecimal.valueOf(6), 2, RoundingMode.HALF_UP) + "/month for 6 months").build());

        return ReinvestmentPlan.builder()
            .totalToRedeploy(totalToRedeploy)
            .tranches(tranches)
            .basis("Rule-based staged plan (20% now / 30% on a pullback / 50% via SIP), not continuous market monitoring or a predicted bottom. Not investment advice.")
            .build();
    }
}
