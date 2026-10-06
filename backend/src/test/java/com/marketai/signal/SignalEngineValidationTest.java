package com.marketai.signal;

import com.marketai.market.entity.PriceHistory;
import com.marketai.market.quality.PriceSeriesValidator;
import com.marketai.market.service.MarketDataService;
import com.marketai.signal.dto.SignalPayload;
import com.marketai.signal.service.MarketStructureAnalyzer;
import com.marketai.signal.service.SignalEngine;
import com.marketai.support.Bars;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A BUY/SELL is shown only when that call has beaten the base rate on the instrument's own history. */
class SignalEngineValidationTest {

    private static final LocalDate END = LocalDate.of(2026, 9, 29);

    private SignalPayload run(List<PriceHistory> bars, LocalDate expected) {
        MarketDataService md = mock(MarketDataService.class);
        when(md.getDailySeries(anyString(), anyInt())).thenAnswer(inv ->
                PriceSeriesValidator.validate("TEST", bars, expected, inv.getArgument(1)));
        return new SignalEngine(md, new MarketStructureAnalyzer()).analyse("TEST");
    }

    @Test
    @DisplayName("On random walks the rule has no edge, so a validated BUY/SELL is at most a rare false positive")
    void randomWalkHasNoEdge() {
        int ruleCalls = 0, shown = 0;
        for (long seed = 1; seed <= 8; seed++) {
            SignalPayload p = run(Bars.randomWalk(1200, 0.015, seed, END), END);
            assertThat(p.getValidation().getObservations()).isGreaterThan(900);
            if (p.getRuleOutput() == SignalPayload.Type.BUY || p.getRuleOutput() == SignalPayload.Type.SELL) ruleCalls++;
            if (p.getSignal() == SignalPayload.Type.BUY || p.getSignal() == SignalPayload.Type.SELL) {
                shown++;
                assertThat(p.getValidation().isCurrentCallValidated()).isTrue();
            }
            if (p.getSignal() == SignalPayload.Type.NO_ACTIONABLE_SIGNAL && p.getRuleOutput() != SignalPayload.Type.HOLD) {
                assertThat(p.getGuardrails()).extracting("rule").contains("UNVALIDATED_RULE");
            }
        }
        assertThat(shown).isLessThanOrEqualTo(1);
        assertThat(shown).isLessThanOrEqualTo(ruleCalls);
    }

    @Test
    @DisplayName("An unvalidated call carries no confidence number and no stop/target")
    void unvalidatedHasNoNumbers() {
        SignalPayload p = run(Bars.randomWalk(1200, 0.015, 11, END), END);
        if (p.getSignal() == SignalPayload.Type.NO_ACTIONABLE_SIGNAL || p.getSignal() == SignalPayload.Type.HOLD) {
            assertThat(p.getConfidence()).isNull();
            assertThat(p.getExecution()).isNull();
        }
    }

    @Test
    @DisplayName("Stale history gives STALE_DATA, not a signal")
    void stale() {
        SignalPayload p = run(Bars.randomWalk(400, 0.015, 2, END), END.plusDays(10));
        assertThat(p.getSignal()).isEqualTo(SignalPayload.Type.STALE_DATA);
        assertThat(p.getGuardrails()).extracting("rule").contains("STALE_DATA");
    }

    @Test
    @DisplayName("Too little history gives INSUFFICIENT_DATA")
    void insufficient() {
        assertThat(run(Bars.randomWalk(30, 0.015, 2, END), END).getSignal()).isEqualTo(SignalPayload.Type.INSUFFICIENT_DATA);
    }

    private static List<PriceHistory> trending(int n, long seed) {
        java.util.Random r = new java.util.Random(seed);
        var dates = Bars.weekdaysEndingOn(END, n);
        List<PriceHistory> out = new java.util.ArrayList<>();
        double c = 1000, drift = 0;
        for (int i = 0; i < n; i++) {
            if (i % 60 == 0) drift = (r.nextBoolean() ? 1 : -1) * 0.0025;   // persistent up/down regimes
            double prev = c;
            c = prev * Math.exp(drift + 0.01 * r.nextGaussian());
            out.add(Bars.bar(dates.get(i), prev, Math.max(prev, c) * 1.003, Math.min(prev, c) * 0.997, c, 100000 + r.nextInt(50000)));
        }
        return out;
    }

    @Test
    @DisplayName("Calls are counted once per 20-session window, so a stock with a real, persistent edge can reach a validated signal")
    void realEdgeCanValidate() {
        int shown = 0;
        for (long seed = 1; seed <= 12; seed++) {
            SignalPayload p = run(trending(2400, seed), END);
            var v = p.getValidation();
            // independent calls: never more than one per 20 sessions of replayed history
            assertThat(v.getBuyCalls()).isLessThanOrEqualTo(v.getObservations() / 20 + 1);
            assertThat(v.getSellCalls()).isLessThanOrEqualTo(v.getObservations() / 20 + 1);
            if (p.getSignal() == SignalPayload.Type.BUY || p.getSignal() == SignalPayload.Type.SELL) {
                shown++;
                assertThat(v.isCurrentCallValidated()).isTrue();
            }
        }
        assertThat(shown).as("at least one trending series should validate; before the fix none ever could").isGreaterThanOrEqualTo(1);
    }
}
