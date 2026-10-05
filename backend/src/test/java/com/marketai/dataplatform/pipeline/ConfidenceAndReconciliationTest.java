package com.marketai.dataplatform.pipeline;

import com.marketai.dataplatform.domain.ReconStatus;
import com.marketai.dataplatform.domain.SourceType;
import com.marketai.dataplatform.pipeline.ConfidenceCalculator.Observation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static com.marketai.dataplatform.pipeline.TestTxns.obs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ConfidenceAndReconciliationTest {

    private final ConfidenceCalculator conf = new ConfidenceCalculator();
    private final ReconciliationEngine engine = new ReconciliationEngine();
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 5, 10, 0);
    private final Duration grace = Duration.ofDays(7);

    /* source priority */

    @Test @DisplayName("source hierarchy: authoritative sources outrank email, manual and AI")
    void sourceHierarchy() {
        assertThat(SourceType.ACCOUNT_AGGREGATOR.moreReliableThan(SourceType.BROKER_API)).isTrue();
        assertThat(SourceType.BROKER_API.moreReliableThan(SourceType.CAS)).isTrue();
        assertThat(SourceType.CAS.moreReliableThan(SourceType.STATEMENT)).isTrue();
        assertThat(SourceType.STATEMENT.moreReliableThan(SourceType.EMAIL)).isTrue();
        assertThat(SourceType.EMAIL.moreReliableThan(SourceType.MANUAL)).isTrue();
        assertThat(SourceType.MANUAL.moreReliableThan(SourceType.AI_INFERENCE)).isTrue();
        assertThat(SourceType.STATEMENT.authoritative()).isTrue();
        assertThat(SourceType.EMAIL.authoritative()).isFalse();
        assertThat(SourceType.AI_INFERENCE.authoritative()).isFalse();
    }

    /* confidence */

    @Test @DisplayName("confidence: a structured authoritative record is fully trusted; an email is scaled by extraction confidence")
    void confidenceBase() {
        assertThat(conf.confidence(List.of(new Observation(SourceType.ACCOUNT_AGGREGATOR, 1.0)), false)).isEqualTo(1.0);
        assertThat(conf.confidence(List.of(new Observation(SourceType.EMAIL, 0.8)), false)).isCloseTo(0.6, within(0.001));
    }

    @Test @DisplayName("confidence: each further independent source type adds 0.05, capped at 1")
    void confidenceCorroboration() {
        double c = conf.confidence(List.of(new Observation(SourceType.EMAIL, 1.0), new Observation(SourceType.MANUAL, 1.0)), false);
        assertThat(c).isCloseTo(0.80, within(0.001));
        assertThat(conf.confidence(List.of(new Observation(SourceType.ACCOUNT_AGGREGATOR, 1.0), new Observation(SourceType.EMAIL, 1.0)), false)).isEqualTo(1.0);
        // the same source type twice is not independent corroboration
        assertThat(conf.confidence(List.of(new Observation(SourceType.EMAIL, 1.0), new Observation(SourceType.EMAIL, 1.0)), false)).isCloseTo(0.75, within(0.001));
    }

    @Test @DisplayName("confidence: a conflict caps it at 0.5; nothing observed is zero")
    void confidenceConflict() {
        assertThat(conf.confidence(List.of(new Observation(SourceType.ACCOUNT_AGGREGATOR, 1.0)), true)).isEqualTo(0.5);
        assertThat(conf.confidence(List.of(), false)).isZero();
    }

    /* reconciliation */

    @Test @DisplayName("an authoritative source verifies")
    void verified() {
        var ev = engine.evaluate(List.of(obs(SourceType.EMAIL, null, "10000"), obs(SourceType.ACCOUNT_AGGREGATOR, null, "10000")), now.minusDays(1), now, grace);
        assertThat(ev.status()).isEqualTo(ReconStatus.VERIFIED);
        assertThat(ev.sourcesDisagree()).isFalse();
    }

    @Test @DisplayName("two independent weak sources agreeing is MATCHED, not VERIFIED")
    void matched() {
        var ev = engine.evaluate(List.of(obs(SourceType.EMAIL, null, "10000"), obs(SourceType.MANUAL, null, "10000")), now.minusDays(1), now, grace);
        assertThat(ev.status()).isEqualTo(ReconStatus.MATCHED);
    }

    @Test @DisplayName("a single email waits, then becomes UNCONFIRMED after the grace period — never silently treated as fact")
    void unconfirmedAfterGrace() {
        var one = List.of(obs(SourceType.EMAIL, null, "10000"));
        assertThat(engine.evaluate(one, now.minusDays(3), now, grace).status()).isEqualTo(ReconStatus.PENDING);
        assertThat(engine.evaluate(one, now.minusDays(8), now, grace).status()).isEqualTo(ReconStatus.UNCONFIRMED);
    }

    @Test @DisplayName("an authoritative source overrides a disagreeing email: partially matched, authority wins")
    void authorityOverridesEmail() {
        var ev = engine.evaluate(List.of(obs(SourceType.EMAIL, null, "10000"), obs(SourceType.ACCOUNT_AGGREGATOR, null, "12000")), now, now, grace);
        assertThat(ev.status()).isEqualTo(ReconStatus.PARTIALLY_MATCHED);
        assertThat(ev.authorityOverrides()).isTrue();
        var c = engine.consolidate(List.of(obs(SourceType.EMAIL, null, "10000"), obs(SourceType.ACCOUNT_AGGREGATOR, null, "12000")));
        assertThat(c.net()).isEqualByComparingTo("12000");
        assertThat(c.lead().type()).isEqualTo(SourceType.ACCOUNT_AGGREGATOR);
    }

    @Test @DisplayName("two authoritative sources that disagree are a CONFLICT")
    void authoritativeConflict() {
        var ev = engine.evaluate(List.of(obs(SourceType.ACCOUNT_AGGREGATOR, null, "10000"), obs(SourceType.ACCOUNT_AGGREGATOR, null, "10500")), now, now, grace);
        assertThat(ev.status()).isEqualTo(ReconStatus.CONFLICT);
    }

    @Test @DisplayName("weak sources that disagree, with no authority to settle it, are a CONFLICT")
    void weakConflict() {
        var ev = engine.evaluate(List.of(obs(SourceType.EMAIL, null, "10000"), obs(SourceType.MANUAL, null, "10500")), now, now, grace);
        assertThat(ev.status()).isEqualTo(ReconStatus.CONFLICT);
    }

    @Test @DisplayName("consolidation takes each field from the most reliable source that stated it")
    void consolidateFillsGaps() {
        var email = obs(SourceType.EMAIL, "52.431", "10000");
        var aa = new SourceObservation(SourceType.ACCOUNT_AGGREGATOR, "aa", null, null, java.time.LocalDate.of(2026, 8, 15), null, null,
            new java.math.BigDecimal("10000"), null, null, new java.math.BigDecimal("10000"), 1.0);
        var c = engine.consolidate(List.of(email, aa));
        assertThat(c.quantity()).isEqualByComparingTo("52.431");   // only the email stated it
        assertThat(c.lead().type()).isEqualTo(SourceType.ACCOUNT_AGGREGATOR);
    }
}
