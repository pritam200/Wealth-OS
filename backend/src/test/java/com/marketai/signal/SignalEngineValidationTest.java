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
}
