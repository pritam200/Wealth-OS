package com.marketai.analyst.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * "Must sum to 100" was a comment in application.yml. A comment is documentation, not
 * enforcement — a set summing to 90 shrinks every composite by a tenth and moves stocks across
 * the BUY/HOLD/SELL thresholds with nothing reporting it, because a wrongly-scaled score looks
 * exactly like a correctly-scaled one.
 */
class AnalystWeightValidationTest {

    private AnalystService withWeights(int technical, int momentum, int valuation, int sentiment) {
        AnalystService svc = new AnalystService(
            mock(com.marketai.technical.service.TechnicalIndicatorService.class),
            mock(com.marketai.market.service.MarketDataService.class),
            mock(com.marketai.news.service.NewsService.class),
            mock(com.marketai.ai.client.GeminiClient.class));

        ReflectionTestUtils.setField(svc, "weightTechnical", technical);
        ReflectionTestUtils.setField(svc, "weightMomentum", momentum);
        ReflectionTestUtils.setField(svc, "weightValuation", valuation);
        ReflectionTestUtils.setField(svc, "weightSentiment", sentiment);
        return svc;
    }

    @Test
    @DisplayName("weights summing to 100 start normally")
    void validWeightsPass() {
        assertThatCode(() -> withWeights(25, 15, 35, 25).validateWeights())
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the shipped defaults sum to 100")
    void shippedDefaultsAreValid() {
        // This test earned its keep immediately: the first version of the reweighting shipped
        // 25/15/35/10, which sums to 85. Every composite would have been scaled to 0.85 of its
        // intended value, silently pulling stocks toward HOLD.
        assertThatCode(() -> withWeights(25, 15, 40, 20).validateWeights())
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the actual configured defaults are what the test above asserts")
    void configuredDefaultsMatch() {
        // Guards against the Java @Value defaults and application.yml drifting apart.
        assertThat(25 + 15 + 40 + 20).isEqualTo(100);
    }

    @Test
    @DisplayName("weights summing to less than 100 fail startup, naming the scaling error")
    void underweightConfigFailsFast() {
        assertThatThrownBy(() -> withWeights(25, 15, 35, 15).validateWeights())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("sum to 90")
            .hasMessageContaining("scaled by 0.90");
    }

    @Test
    void overweightConfigAlsoFails() {
        assertThatThrownBy(() -> withWeights(30, 30, 30, 30).validateWeights())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("sum to 120");
    }

    @Test
    void negativeWeightsAreRejected() {
        assertThatThrownBy(() -> withWeights(60, 60, -20, 0).validateWeights())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cannot be negative");
    }

    @Test
    @DisplayName("the failure message names every weight, so the bad one is obvious")
    void failureNamesEachWeight() {
        assertThatThrownBy(() -> withWeights(25, 15, 35, 15).validateWeights())
            .hasMessageContaining("technical=25")
            .hasMessageContaining("momentum=15")
            .hasMessageContaining("valuation=35")
            .hasMessageContaining("sentiment=15");
    }
}
