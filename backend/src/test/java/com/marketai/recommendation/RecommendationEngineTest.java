package com.marketai.recommendation;

import com.marketai.analyst.dto.AnalystAssessment;
import com.marketai.analyst.service.AnalystService;
import com.marketai.market.entity.PriceHistory;
import com.marketai.market.quality.DailySeries;
import com.marketai.market.quality.SeriesStatus;
import com.marketai.market.service.MarketDataService;
import com.marketai.mf.dto.MfPerformanceMetrics;
import com.marketai.mf.service.MfPeerComparisonService;
import com.marketai.recommendation.dto.MfRecommendationRequest;
import com.marketai.recommendation.service.RecommendationEngine;
import com.marketai.signal.dto.SignalPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Actions come only from verified inputs; without a validated signal the answer says so. */
class RecommendationEngineTest {

    private final AnalystService analyst = mock(AnalystService.class);
    private final MarketDataService md = mock(MarketDataService.class);
    private final RecommendationEngine engine = new RecommendationEngine(analyst, md);

    private AnalystAssessment assessment(String rating, String trend, Integer hitRate) {
        return AnalystAssessment.builder().symbol("X").rating(rating).confidenceScore(hitRate)
                .dataQuality("FULL").barsAvailable(2500).priceDate(LocalDate.of(2026, 9, 29))
                .ruleOutput(rating)
                .signalValidation(SignalPayload.Validation.builder().summary("record").baseUpRate(0.52).build())
                .fundamentals(AnalystAssessment.Fundamentals.builder().trend(trend).build())
                .factorBreakdown(new ArrayList<>()).positives(new ArrayList<>()).risks(new ArrayList<>()).build();
    }

    @Test
    @DisplayName("No validated signal: the trend describes the position, says so, carries no confidence — and never makes a trade call")
    void trendDrivesTheActionWhenNoCallIsValidated() {
        when(analyst.assess(anyString(), any())).thenReturn(assessment(AnalystService.NO_ACTIONABLE_SIGNAL, "STRONG_DOWNTREND", null));
        AnalystAssessment a = engine.recommend("X", "X", -12.0, 1000.0, 100000.0);
        // The trend label has no tested edge, so a strong downtrend asks for a review, not an exit.
        assertThat(a.getNextAction()).isEqualTo("REVIEW");
        assertThat(a.getNextActionReason()).contains("not a backtested signal").contains("down 12.0%");
        assertThat(a.getConfidenceScore()).isNull();

        // With no position, the trend is context only — no AVOID without a validated SELL.
        when(analyst.assess(anyString(), any())).thenReturn(assessment(AnalystService.NO_ACTIONABLE_SIGNAL, "STRONG_DOWNTREND", null));
        AnalystAssessment watch = engine.recommend("X", "X", null, null, null);
        assertThat(watch.getNextAction()).isEqualTo(AnalystService.NO_ACTIONABLE_SIGNAL);
        assertThat(watch.getNextActionReason()).contains("strong downtrend");
        when(analyst.assess(anyString(), any())).thenReturn(assessment(AnalystService.NO_ACTIONABLE_SIGNAL, "UPTREND", null));
        assertThat(engine.recommend("X", "X", null, null, null).getNextAction()).isEqualTo(AnalystService.NO_ACTIONABLE_SIGNAL);
        when(analyst.assess(anyString(), any())).thenReturn(assessment(AnalystService.NO_ACTIONABLE_SIGNAL, "UPTREND", null));
        assertThat(engine.recommend("X", "X", 5.0, 1000.0, 100000.0).getNextAction()).isEqualTo("CONTINUE");
        when(analyst.assess(anyString(), any())).thenReturn(assessment(AnalystService.NO_ACTIONABLE_SIGNAL, "DOWNTREND", null));
        assertThat(engine.recommend("X", "X", -5.0, 1000.0, 100000.0).getNextAction()).isEqualTo("REVIEW");
        when(analyst.assess(anyString(), any())).thenReturn(assessment(AnalystService.NO_ACTIONABLE_SIGNAL, "SIDEWAYS", null));
        assertThat(engine.recommend("X", "X", 1.0, 1000.0, 100000.0).getNextAction()).isEqualTo("HOLD");
    }

    @Test
    @DisplayName("A validated BUY accumulates — unless the position is already above the 25% guideline")
    void validatedBuy() {
        when(analyst.assess(anyString(), any())).thenReturn(assessment("BUY", "UPTREND", 61));
        assertThat(engine.recommend("X", "X", 5.0, 1000.0, 100000.0).getNextAction()).isEqualTo("ACCUMULATE");
        when(analyst.assess(anyString(), any())).thenReturn(assessment("BUY", "UPTREND", 61));
        assertThat(engine.recommend("X", "X", 5.0, 40000.0, 100000.0).getNextAction()).isEqualTo("HOLD");
    }

    @Test
    @DisplayName("Research can stop a call, never start one: conflicting evidence turns a validated BUY into REVIEW")
    void conflictingResearchHoldsBackTheCall() {
        AnalystAssessment a = assessment("BUY", "UPTREND", 61);
        a.setResearchActionability("CONFLICTING_EVIDENCE");
        a.setResearchReason("The validated quantitative call is BUY but research leans SELL.");
        when(analyst.assess(anyString(), any())).thenReturn(a);
        AnalystAssessment out = engine.recommend("X", "X", 5.0, 1000.0, 100000.0);
        assertThat(out.getNextAction()).isEqualTo("REVIEW");
        assertThat(out.getNextActionReason()).contains("research leans SELL");

        // research leaning BUY with no validated signal does not create a call: the action is the
        // trend's (CONTINUE), never ACCUMULATE
        AnalystAssessment none = assessment(AnalystService.NO_ACTIONABLE_SIGNAL, "UPTREND", null);
        none.setResearchActionability("NO_ACTIONABLE_SIGNAL");
        none.setResearchLean("BUY");
        when(analyst.assess(anyString(), any())).thenReturn(none);
        assertThat(engine.recommend("X", "X", 5.0, 1000.0, 100000.0).getNextAction()).isEqualTo("CONTINUE");
    }

    @Test
    @DisplayName("Overweight with no signal → REBALANCE (a diversification guideline, not a market call)")
    void overweight() {
        when(analyst.assess(anyString(), any())).thenReturn(assessment(AnalystService.NO_ACTIONABLE_SIGNAL, "SIDEWAYS", null));
        assertThat(engine.recommend("X", "X", 5.0, 40000.0, 100000.0).getNextAction()).isEqualTo("REBALANCE");
    }

    @Test
    @DisplayName("Stale data → STALE_DATA, no action")
    void stale() {
        when(analyst.assess(anyString(), any())).thenReturn(assessment("STALE_DATA", "UPTREND", null));
        assertThat(engine.recommend("X", "X").getNextAction()).isEqualTo("STALE_DATA");
    }

    @Test
    @DisplayName("A symbol that has stopped getting prices says it may be suspended or delisted")
    void staleAndInactive() {
        when(analyst.assess(anyString(), any())).thenReturn(assessment("STALE_DATA", "UPTREND", null));
        when(md.isInactive("X")).thenReturn(true);
        AnalystAssessment a = engine.recommend("X", "X");
        assertThat(a.getNextAction()).isEqualTo("STALE_DATA");
        assertThat(a.getNextActionReason()).contains("suspended or delisted");
    }

    @Test
    @DisplayName("History still downloading: no data yet, and the reason says it is coming rather than missing")
    void historyPending() {
        AnalystAssessment pending = AnalystAssessment.builder().symbol("X").rating("INSUFFICIENT_DATA")
                .dataQuality("INSUFFICIENT").barsAvailable(0).historyPending(true)
                .factorBreakdown(new ArrayList<>()).positives(new ArrayList<>()).risks(new ArrayList<>()).build();
        when(analyst.assess(anyString(), any())).thenReturn(pending);
        AnalystAssessment a = engine.recommend("X", "X");
        assertThat(a.getNextAction()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(a.isHistoryPending()).isTrue();
        assertThat(a.getNextActionReason()).contains("still downloading");
    }

    @Test
    @DisplayName("A fund symbol is never sent to the stock engine, which would look for an exchange feed it doesn't have")
    void fundSymbolOnTheStockEndpoint() {
        AnalystAssessment a = engine.recommend("HDFCFLEXI.MF", null);
        assertThat(a.getRating()).isEqualTo("NOT_RATED");
        assertThat(a.getNextAction()).isEqualTo("INSUFFICIENT_DATA");
        org.mockito.Mockito.verifyNoInteractions(analyst);
    }

    @Test
    @DisplayName("Mutual funds are not rated and carry no made-up confidence")
    void mfNotRated() {
        when(md.getDailySeries(anyString(), anyInt())).thenThrow(new RuntimeException("no data"));
        MfRecommendationRequest req = new MfRecommendationRequest();
        req.setSymbol("F.MF");
        req.setBuyDate(LocalDate.now().minusYears(2));
        req.setInvestedValue(new BigDecimal("100000"));
        req.setCurrentValue(new BigDecimal("125000"));
        req.setXirr(new BigDecimal("11.5"));
        req.setSipStatus("ACTIVE");
        AnalystAssessment a = engine.recommendMf(req);
        assertThat(a.getRating()).isEqualTo("NOT_RATED");
        assertThat(a.getConfidenceScore()).isNull();
        assertThat(a.getCompositeScore()).isNull();
        assertThat(a.getNextAction()).isEqualTo("CONTINUE_SIP");
    }

    private MfRecommendationRequest fund(LocalDate bought, String invested, String current, String xirr, String sip) {
        MfRecommendationRequest req = new MfRecommendationRequest();
        req.setSymbol("F.MF");
        req.setBuyDate(bought);
        req.setInvestedValue(new BigDecimal(invested));
        req.setCurrentValue(new BigDecimal(current));
        req.setXirr(xirr == null ? null : new BigDecimal(xirr));
        req.setSipStatus(sip);
        return req;
    }

    @Test
    @DisplayName("A fund with no SIP on record is a lump sum: on track means hold, not continue a SIP")
    void lumpSumOnTrackIsHold() {
        when(md.getDailySeries(anyString(), anyInt())).thenThrow(new RuntimeException("no data"));
        AnalystAssessment a = engine.recommendMf(fund(LocalDate.now().minusYears(2), "100000", "125000", "11.5", "NONE"));
        assertThat(a.getNextAction()).isEqualTo("HOLD");
    }

    @Test
    @DisplayName("A loss alone is a review, not a switch: without the fund's record or ranking there is no evidence the fund is the problem")
    void lossWithoutEvidenceIsReview() {
        when(md.getDailySeries(anyString(), anyInt())).thenThrow(new RuntimeException("no data"));
        for (String sip : List.of("ACTIVE", "INFERRED", "NONE")) {
            AnalystAssessment a = engine.recommendMf(fund(LocalDate.now().minusYears(2), "100000", "85000", "-4", sip));
            assertThat(a.getNextAction()).isEqualTo("REVIEW");
            assertThat(a.getNextActionReason()).contains("don't sell on the loss alone");
        }
    }

    /** Nifty 50 rising 10% a year for four years, weekly closes. */
    private DailySeries nifty() {
        List<PriceHistory> bars = new ArrayList<>();
        LocalDate start = LocalDate.now().minusYears(4).minusDays(14);
        for (LocalDate d = start; !d.isAfter(LocalDate.now()); d = d.plusDays(7)) {
            double years = java.time.temporal.ChronoUnit.DAYS.between(start, d) / 365.25;
            bars.add(PriceHistory.builder().symbol("^NSEI").date(d).close(BigDecimal.valueOf(100 * Math.pow(1.10, years))).build());
        }
        return new DailySeries("^NSEI", bars, SeriesStatus.OK, List.of(), 0, LocalDate.now(), 0, "test", null);
    }

    private void fundRecord(String r1, String r3) {
        MfPeerComparisonService peers = mock(MfPeerComparisonService.class);
        MfPerformanceMetrics m = MfPerformanceMetrics.builder().observationCount(900)
            .return1Y(new BigDecimal(r1)).return3YCagr(new BigDecimal(r3)).build();
        when(peers.compare("123")).thenReturn(Optional.of(new MfPeerComparisonService.Comparison(
            "123", "F", "Equity Scheme - Flexi Cap Fund", "Direct", 0, 0, true, m, List.of(), List.of())));
        ReflectionTestUtils.setField(engine, "mfPeers", peers);
        when(md.getDailySeries(anyString(), anyInt())).thenReturn(nifty());
    }

    private MfRecommendationRequest linked(MfRecommendationRequest r) { r.setSchemeCode("123"); return r; }

    @Test
    @DisplayName("Down, but the fund has beaten the Nifty for years: hold (or keep the SIP) — the fall is the market")
    void lossInAStrongFundIsHold() {
        fundRecord("14", "16");
        AnalystAssessment a = engine.recommendMf(linked(fund(null, "100000", "88000", null, "NONE")));
        assertThat(a.getNextAction()).isEqualTo("HOLD");
        assertThat(a.getNextActionReason()).contains("the fall is the market");
        assertThat(engine.recommendMf(linked(fund(null, "100000", "88000", null, "ACTIVE"))).getNextAction()).isEqualTo("CONTINUE_SIP");
    }

    @Test
    @DisplayName("Down and behind the Nifty for years: redirect the SIP; a lump sum is reviewed, not sold on the loss")
    void lossInAWeakFund() {
        fundRecord("3", "4");
        assertThat(engine.recommendMf(linked(fund(null, "100000", "88000", null, "ACTIVE"))).getNextAction()).isEqualTo("REDIRECT_SIP");
        AnalystAssessment lump = engine.recommendMf(linked(fund(null, "100000", "88000", null, "NONE")));
        assertThat(lump.getNextAction()).isEqualTo("REVIEW");
        assertThat(lump.getNextActionReason()).contains("lagged the market for years");
    }

    @Test
    @DisplayName("A strong fund without a purchase date is still an add: top up a lump sum, step up a SIP")
    void strongFundWithoutDateIsAdd() {
        fundRecord("14", "16");
        assertThat(engine.recommendMf(linked(fund(null, "100000", "104000", null, "NONE"))).getNextAction()).isEqualTo("TOP_UP");
        assertThat(engine.recommendMf(linked(fund(null, "100000", "104000", null, "ACTIVE"))).getNextAction()).isEqualTo("INCREASE_SIP");
    }

    @Test
    @DisplayName("A strong long-term lump sum is a top-up candidate; a strong SIP is a step-up")
    void strongFundTopUpOrIncrease() {
        when(md.getDailySeries(anyString(), anyInt())).thenThrow(new RuntimeException("no data"));
        assertThat(engine.recommendMf(fund(LocalDate.now().minusYears(3), "100000", "160000", "17", "NONE")).getNextAction())
            .isEqualTo("TOP_UP");
        assertThat(engine.recommendMf(fund(LocalDate.now().minusYears(3), "100000", "160000", "17", "ACTIVE")).getNextAction())
            .isEqualTo("INCREASE_SIP");
    }

    @Test
    @DisplayName("An unknown purchase date is neither 'too recent' nor long-term, and tax is not guessed")
    void unknownPurchaseDate() {
        AnalystAssessment a = engine.recommendMf(fund(null, "100000", "110000", null, "NONE"));
        assertThat(a.getNextAction()).isEqualTo("HOLD");
        assertThat(a.getTaxImpact()).contains("purchase dates are not on record").doesNotContain("LTCG:");
    }

    @Test
    @DisplayName("A stock's assessment is reused for a while, and each position gets its own copy")
    void assessmentReusedWithoutSharingPositions() {
        org.springframework.test.util.ReflectionTestUtils.setField(engine, "reuseAssessments", true);
        when(analyst.assess(anyString(), any())).thenReturn(assessment(AnalystService.NO_ACTIONABLE_SIGNAL, "UPTREND", null));

        AnalystAssessment big = engine.recommend("X", "X", 5.0, 40000.0, 100000.0);
        AnalystAssessment small = engine.recommend("X", "X", 5.0, 1000.0, 100000.0);

        org.mockito.Mockito.verify(analyst, org.mockito.Mockito.times(1)).assess(anyString(), any());
        assertThat(big.getFactorBreakdown()).singleElement().satisfies(f -> assertThat(f.getReading()).isEqualTo("40%"));
        assertThat(small.getFactorBreakdown()).singleElement().satisfies(f -> assertThat(f.getReading()).isEqualTo("1%"));
        assertThat(small.getSignalValidation().getSummary()).isEqualTo("record");
        assertThat(small.getPriceDate()).isEqualTo(LocalDate.of(2026, 9, 29));
    }
}
