package com.marketai.actions;

import com.marketai.actions.dto.TodaysActionsResponse;
import com.marketai.actions.service.TodaysActionsService;
import com.marketai.analyst.dto.AnalystAssessment;
import com.marketai.portfolio.entity.Holding;
import com.marketai.recommendation.dto.MfRecommendationRequest;
import com.marketai.recommendation.dto.PortfolioContext;
import com.marketai.recommendation.service.PortfolioContextService;
import com.marketai.recommendation.service.RecommendationEngine;
import com.marketai.technical.dto.TechnicalAnalysisDto;
import com.marketai.technical.service.TechnicalIndicatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TodaysActionsServiceTest {

    private static final Long USER = 1L;

    private PortfolioContextService ctxService;
    private RecommendationEngine engine;
    private TechnicalIndicatorService technical;
    private TodaysActionsService service;

    @BeforeEach
    void setup() {
        ctxService = mock(PortfolioContextService.class);
        engine = mock(RecommendationEngine.class);
        technical = mock(TechnicalIndicatorService.class);
        service = new TodaysActionsService(ctxService, engine, technical);

        when(ctxService.build(USER)).thenReturn(emptyContext());
        when(ctxService.getAllHoldings(USER)).thenReturn(Collections.<Holding>emptyList());
        // Reinvestment-plan trigger computation calls this — keep it harmless by default.
        when(technical.analyse(anyString())).thenReturn(TechnicalAnalysisDto.builder().build());
    }

    private PortfolioContext emptyContext() {
        return PortfolioContext.builder()
            .stocksValue(BigDecimal.ZERO).mfValue(BigDecimal.ZERO)
            .totalAssets(BigDecimal.ZERO).netWorth(BigDecimal.ZERO)
            .concentrationFlags(new ArrayList<>()).dataGaps(new ArrayList<>())
            .build();
    }

    private Holding stockHolding(String symbol, double qty, double avg, double price, BigDecimal pnlPct) {
        return Holding.builder()
            .id(1L).symbol(symbol).name(symbol.replace(".NS", ""))
            .quantity(BigDecimal.valueOf(qty)).averageCost(BigDecimal.valueOf(avg))
            .currentPrice(BigDecimal.valueOf(price))
            .build();
    }

    private AnalystAssessment assessment(String nextAction, String reason, int confidence, List<String> risks) {
        return AnalystAssessment.builder()
            .nextAction(nextAction).nextActionReason(reason).confidenceScore(confidence)
            .risks(risks != null ? risks : new ArrayList<>())
            .build();
    }

    @Test
    @DisplayName("ACCUMULATE routes to the Buy bucket with a concentration-bounded max-add, not a guessed amount")
    void accumulateRoutesToBuy() {
        Holding h = stockHolding("RELIANCE.NS", 10, 1000, 1200, null);
        when(ctxService.getAllHoldings(USER)).thenReturn(Collections.singletonList(h));
        PortfolioContext ctx = PortfolioContext.builder()
            .stocksValue(BigDecimal.valueOf(48000)).mfValue(BigDecimal.ZERO)
            .totalAssets(BigDecimal.valueOf(48000)).netWorth(BigDecimal.valueOf(48000))
            .concentrationFlags(new ArrayList<>()).dataGaps(new ArrayList<>())
            .build();
        when(ctxService.build(USER)).thenReturn(ctx);
        when(engine.recommend(eq("RELIANCE.NS"), anyString(), any(), any(), any()))
            .thenReturn(assessment("ACCUMULATE", "Trend and model agree.", 62, null));

        TodaysActionsResponse resp = service.build(USER);

        assertThat(resp.getBuy()).hasSize(1);
        TodaysActionsResponse.BuyAction b = resp.getBuy().get(0);
        assertThat(b.getSymbol()).isEqualTo("RELIANCE.NS");
        // 25% of 48000 = 12000 ceiling; holding is already worth 12000 → nothing left to add.
        assertThat(b.getMaxAddWithoutBreachingGuideline()).isEqualByComparingTo("0.00");
        assertThat(resp.getSellReduce()).isEmpty();
        assertThat(resp.getHold()).isEmpty();
    }

    @Test
    @DisplayName("BOOK_PROFIT applies the correct tier and builds a 20/30/50 reinvestment plan")
    void bookProfitTieredPercentAndReinvestmentPlan() {
        Holding h = Holding.builder()
            .id(2L).symbol("BIGCO.NS").name("BigCo")
            .quantity(BigDecimal.valueOf(10)).averageCost(BigDecimal.valueOf(1000))
            .currentPrice(BigDecimal.valueOf(1600)) // 60% gain → top tier
            .build();
        when(ctxService.getAllHoldings(USER)).thenReturn(Collections.singletonList(h));
        when(engine.recommend(eq("BIGCO.NS"), anyString(), any(), any(), any()))
            .thenReturn(assessment("BOOK_PROFIT", "Up sharply, trim some.", 40, Arrays.asList("Overbought")));

        TodaysActionsResponse resp = service.build(USER);

        assertThat(resp.getBookProfit()).hasSize(1);
        TodaysActionsResponse.BookProfitAction bp = resp.getBookProfit().get(0);
        assertThat(bp.getSuggestedBookPercent()).isEqualTo(30.0); // >=50% gain tier
        // current value = 16000, 30% = 4800
        assertThat(bp.getSuggestedBookAmount()).isEqualByComparingTo("4800.00");
        assertThat(bp.getReinvestmentPlan()).isNotNull();
        assertThat(bp.getReinvestmentPlan().getTranches()).hasSize(3);
        BigDecimal sum = bp.getReinvestmentPlan().getTranches().stream()
            .map(TodaysActionsResponse.ReinvestmentPlan.Tranche::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(bp.getSuggestedBookAmount());
    }

    @Test
    @DisplayName("MF EXIT-equivalent (SWITCH_FUND) carries taxImpact through to Sell/Reduce")
    void switchFundCarriesTaxImpact() {
        Holding h = Holding.builder()
            .id(3L).symbol("BADFUND.MF").name("Bad Fund")
            .quantity(BigDecimal.valueOf(100)).averageCost(BigDecimal.valueOf(50))
            .currentPrice(BigDecimal.valueOf(40))
            .build();
        when(ctxService.getAllHoldings(USER)).thenReturn(Collections.singletonList(h));
        when(engine.recommendMf(any(MfRecommendationRequest.class)))
            .thenReturn(AnalystAssessment.builder()
                .nextAction("SWITCH_FUND").nextActionReason("Negative XIRR, persistent underperformance.")
                .confidenceScore(65).taxImpact("STCG at 15% if redeemed today").risks(new ArrayList<>())
                .build());

        TodaysActionsResponse resp = service.build(USER);

        assertThat(resp.getSellReduce()).hasSize(1);
        assertThat(resp.getSellReduce().get(0).getTaxImpact()).isEqualTo("STCG at 15% if redeemed today");
        assertThat(resp.getSellReduce().get(0).getAction()).isEqualTo("SWITCH");
    }

    @Test
    @DisplayName("INSUFFICIENT_DATA is never dropped and never treated as HOLD")
    void insufficientDataGoesToNotAnalysed() {
        Holding h = stockHolding("NEWCO.NS", 10, 100, 110, null);
        when(ctxService.getAllHoldings(USER)).thenReturn(Collections.singletonList(h));
        when(engine.recommend(eq("NEWCO.NS"), anyString(), any(), any(), any()))
            .thenReturn(assessment("INSUFFICIENT_DATA", "Only 2 days of price history.", 0, null));

        TodaysActionsResponse resp = service.build(USER);

        assertThat(resp.getNotAnalysed()).hasSize(1);
        assertThat(resp.getNotAnalysed().get(0).getReason()).contains("2 days");
        assertThat(resp.getHold()).isEmpty();
        assertThat(resp.getWatch()).isEmpty();
    }

    @Test
    @DisplayName("Portfolio-level concentration flags surface as Watch items even with no per-holding trigger")
    void portfolioFlagsSurfaceAsWatch() {
        PortfolioContext ctx = PortfolioContext.builder()
            .stocksValue(BigDecimal.valueOf(100000)).mfValue(BigDecimal.ZERO)
            .totalAssets(BigDecimal.valueOf(100000)).netWorth(BigDecimal.valueOf(100000))
            .dataGaps(new ArrayList<>())
            .concentrationFlags(Collections.singletonList(
                PortfolioContext.Flag.builder().type("SECTOR").label("Financial Services")
                    .percent(42.0).severity("HIGH").message("42% in Financial Services — correlation risk.")
                    .build()))
            .build();
        when(ctxService.build(USER)).thenReturn(ctx);

        TodaysActionsResponse resp = service.build(USER);

        assertThat(resp.getWatch()).hasSize(1);
        assertThat(resp.getWatch().get(0).getAssetType()).isEqualTo("PORTFOLIO");
        assertThat(resp.getWatch().get(0).getSymbol()).isNull();
    }

    @Test
    @DisplayName("Zero-value holdings are skipped rather than producing a nonsensical action")
    void zeroValueHoldingsSkipped() {
        Holding h = Holding.builder()
            .id(4L).symbol("GONE.NS").name("Gone")
            .quantity(BigDecimal.ZERO).averageCost(BigDecimal.valueOf(100))
            .currentPrice(BigDecimal.ZERO)
            .build();
        when(ctxService.getAllHoldings(USER)).thenReturn(Collections.singletonList(h));

        TodaysActionsResponse resp = service.build(USER);

        assertThat(resp.getBuy()).isEmpty();
        assertThat(resp.getHold()).isEmpty();
        assertThat(resp.getNotAnalysed()).isEmpty();
    }

    @Test
    @DisplayName("A failed engine call surfaces as Not Analysed, never as a fabricated verdict")
    void engineFailureSurfacesAsNotAnalysed() {
        Holding h = stockHolding("FLAKY.NS", 10, 100, 110, null);
        when(ctxService.getAllHoldings(USER)).thenReturn(Collections.singletonList(h));
        when(engine.recommend(eq("FLAKY.NS"), anyString(), any(), any(), any()))
            .thenThrow(new RuntimeException("downstream timeout"));

        TodaysActionsResponse resp = service.build(USER);

        assertThat(resp.getNotAnalysed()).hasSize(1);
        assertThat(resp.getBuy()).isEmpty();
        assertThat(resp.getHold()).isEmpty();
    }
}
