package com.marketai.recommendation;

import com.marketai.analyst.dto.AnalystAssessment;
import com.marketai.analyst.service.AnalystService;
import com.marketai.market.service.MarketDataService;
import com.marketai.recommendation.dto.MfRecommendationRequest;
import com.marketai.recommendation.service.RecommendationEngine;
import com.marketai.signal.dto.SignalPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;

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
    @DisplayName("A strong downtrend alone no longer triggers EXIT: no validated signal → NO_ACTIONABLE_SIGNAL")
    void trendAloneIsNotAnAction() {
        when(analyst.assess(anyString(), any())).thenReturn(assessment(AnalystService.NO_ACTIONABLE_SIGNAL, "STRONG_DOWNTREND", null));
        AnalystAssessment a = engine.recommend("X", "X", -12.0, 1000.0, 100000.0);
        assertThat(a.getNextAction()).isEqualTo("NO_ACTIONABLE_SIGNAL");
        assertThat(a.getConfidenceScore()).isNull();
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

        // research leaning BUY with no validated signal does not create an action
        AnalystAssessment none = assessment(AnalystService.NO_ACTIONABLE_SIGNAL, "UPTREND", null);
        none.setResearchActionability("NO_ACTIONABLE_SIGNAL");
        none.setResearchLean("BUY");
        when(analyst.assess(anyString(), any())).thenReturn(none);
        assertThat(engine.recommend("X", "X", 5.0, 1000.0, 100000.0).getNextAction()).isEqualTo("NO_ACTIONABLE_SIGNAL");
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
    @DisplayName("An underperforming SIP is redirected; an underperforming lump sum is a switch")
    void underperformingSipIsRedirectedLumpSumSwitched() {
        when(md.getDailySeries(anyString(), anyInt())).thenThrow(new RuntimeException("no data"));
        assertThat(engine.recommendMf(fund(LocalDate.now().minusYears(2), "100000", "85000", "-4", "ACTIVE")).getNextAction())
            .isEqualTo("REDIRECT_SIP");
        assertThat(engine.recommendMf(fund(LocalDate.now().minusYears(2), "100000", "85000", "-4", "INFERRED")).getNextAction())
            .isEqualTo("REDIRECT_SIP");
        assertThat(engine.recommendMf(fund(LocalDate.now().minusYears(2), "100000", "85000", "-4", "NONE")).getNextAction())
            .isEqualTo("SWITCH_FUND");
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
}
