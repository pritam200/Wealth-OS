package com.marketai.dataplatform.pipeline;

import com.marketai.dataplatform.domain.IssueSeverity;
import com.marketai.dataplatform.domain.ReconStatus;
import com.marketai.dataplatform.domain.SuspectedCause;
import com.marketai.dataplatform.domain.TransactionType;
import com.marketai.dataplatform.pipeline.LedgerReplay.Entry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerReplayAndQualityTest {

    private static Entry e(long id, TransactionType t, String date, String qty, String price) {
        return new Entry(id, t, LocalDate.parse(date), qty == null ? null : new BigDecimal(qty), price == null ? null : new BigDecimal(price),
            null, null, null, null);
    }

    /* holding calculation */

    @Test @DisplayName("SIPs accumulate units at weighted-average cost")
    void sipAverage() {
        var p = LedgerReplay.replay(List.of(e(1, TransactionType.SIP, "2026-06-15", "100", "10"), e(2, TransactionType.SIP, "2026-07-15", "100", "20")));
        assertThat(p.quantity()).isEqualByComparingTo("200");
        assertThat(p.averageCost()).isEqualByComparingTo("15");
        assertThat(p.investedValue()).isEqualByComparingTo("3000");
    }

    @Test @DisplayName("a sale (or SWP/redemption) reduces units at average cost and realises P&L")
    void saleRealises() {
        var p = LedgerReplay.replay(List.of(e(1, TransactionType.BUY, "2026-06-01", "100", "10"), e(2, TransactionType.SWP, "2026-07-01", "40", "15")));
        assertThat(p.quantity()).isEqualByComparingTo("60");
        assertThat(p.averageCost()).isEqualByComparingTo("10");
        assertThat(p.realizedPnl()).isEqualByComparingTo("200");
    }

    @Test @DisplayName("a switch out then in moves units between two assets (each replays independently)")
    void switchLegs() {
        var outLeg = LedgerReplay.replay(List.of(e(1, TransactionType.BUY, "2026-06-01", "100", "10"), e(2, TransactionType.SWITCH_OUT, "2026-07-01", "100", "12")));
        var inLeg = LedgerReplay.replay(List.of(e(3, TransactionType.SWITCH_IN, "2026-07-01", "50", "24")));
        assertThat(outLeg.quantity()).isZero();
        assertThat(inLeg.quantity()).isEqualByComparingTo("50");
        assertThat(inLeg.investedValue()).isEqualByComparingTo("1200");
    }

    @Test @DisplayName("a stock split scales units and divides cost per unit, total cost unchanged")
    void split() {
        var split = new Entry(2L, TransactionType.SPLIT, LocalDate.parse("2026-07-01"), null, null, null, null, BigDecimal.ONE, new BigDecimal("5"));
        var p = LedgerReplay.replay(List.of(e(1, TransactionType.BUY, "2026-06-01", "10", "1000"), split));
        assertThat(p.quantity()).isEqualByComparingTo("50");
        assertThat(p.averageCost()).isEqualByComparingTo("200");
        assertThat(p.investedValue()).isEqualByComparingTo("10000");
    }

    @Test @DisplayName("bonus units arrive at nil cost, lowering average cost")
    void bonus() {
        var p = LedgerReplay.replay(List.of(e(1, TransactionType.BUY, "2026-06-01", "100", "10"), e(2, TransactionType.BONUS, "2026-07-01", "100", null)));
        assertThat(p.quantity()).isEqualByComparingTo("200");
        assertThat(p.averageCost()).isEqualByComparingTo("5");
    }

    @Test @DisplayName("an oversell is clamped and flagged, never turned into negative units")
    void oversell() {
        var p = LedgerReplay.replay(List.of(e(1, TransactionType.BUY, "2026-06-01", "10", "10"), e(2, TransactionType.SELL, "2026-07-01", "15", "12")));
        assertThat(p.quantity()).isZero();
        assertThat(p.oversold()).isTrue();
    }

    @Test @DisplayName("a purchase without a quantity cannot be applied and is counted as incomplete")
    void incomplete() {
        var p = LedgerReplay.replay(List.of(e(1, TransactionType.BUY, "2026-06-01", "10", "10"), e(2, TransactionType.SIP, "2026-07-01", null, null)));
        assertThat(p.quantity()).isEqualByComparingTo("10");
        assertThat(p.incompleteEntries()).isEqualTo(1);
    }

    @Test @DisplayName("same-day buy and sell is ordered units-in first, so it is not an oversell")
    void sameDay() {
        var p = LedgerReplay.replay(List.of(e(2, TransactionType.SELL, "2026-06-01", "10", "12"), e(1, TransactionType.BUY, "2026-06-01", "10", "10")));
        assertThat(p.oversold()).isFalse();
    }

    @Test @DisplayName("replay is deterministic regardless of input order")
    void orderIndependent() {
        var a = e(1, TransactionType.BUY, "2026-06-01", "100", "10");
        var b = e(2, TransactionType.BUY, "2026-07-01", "50", "12");
        var c = e(3, TransactionType.SELL, "2026-08-01", "30", "15");
        assertThat(LedgerReplay.replay(List.of(a, b, c))).isEqualTo(LedgerReplay.replay(List.of(c, b, a)));
    }

    /* holding reconciliation */

    private final HoldingReconciler reconciler = new HoldingReconciler();

    @Test @DisplayName("a holding mismatch reports the difference, a severity and the causes the evidence supports")
    void holdingMismatch() {
        var r = reconciler.compare(new BigDecimal("1245.21"), new BigDecimal("1257.21"), HoldingReconciler.Evidence.none());
        assertThat(r.matches()).isFalse();
        assertThat(r.difference()).isEqualByComparingTo("12");
        assertThat(r.causes()).contains(SuspectedCause.MISSING_TRANSACTION);
        assertThat(r.severity()).isEqualTo(IssueSeverity.HIGH);   // 12 / 1257 is about 0.95%
    }

    @Test @DisplayName("a large relative difference is HIGH severity")
    void highSeverity() {
        assertThat(reconciler.compare(new BigDecimal("100"), new BigDecimal("200"), HoldingReconciler.Evidence.none()).severity()).isEqualTo(IssueSeverity.HIGH);
    }

    @Test @DisplayName("a matching position within unit tolerance reports no issue")
    void holdingMatches() {
        assertThat(reconciler.compare(new BigDecimal("100.0001"), new BigDecimal("100"), HoldingReconciler.Evidence.none()).matches()).isTrue();
    }

    @Test @DisplayName("the ledger holding more than the institution points to a duplicate when one has that quantity")
    void duplicateCause() {
        var ev = new HoldingReconciler.Evidence(List.of(), List.of(new BigDecimal("12")), false, false, false, false, null, null, false);
        var r = reconciler.compare(new BigDecimal("1269.21"), new BigDecimal("1257.21"), ev);
        assertThat(r.causes().get(0)).isEqualTo(SuspectedCause.DUPLICATE_TRANSACTION);
    }

    @Test @DisplayName("corporate actions, switches and a stale report each add their cause")
    void otherCauses() {
        var ev = new HoldingReconciler.Evidence(List.of(), List.of(), true, true, true, true,
            LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-01"), false);
        var r = reconciler.compare(new BigDecimal("100"), new BigDecimal("105"), ev);
        assertThat(r.causes()).contains(SuspectedCause.CORPORATE_ACTION, SuspectedCause.SWITCH,
            SuspectedCause.DIVIDEND_REINVESTMENT, SuspectedCause.MANUAL_ADJUSTMENT, SuspectedCause.DATA_DELAY);
    }

    /* data quality */

    private final DataQualityScorer scorer = new DataQualityScorer();

    private static Map<ReconStatus, Long> counts(long verified, long matched, long pending, long unconfirmed, long conflict, long missing, long duplicate) {
        Map<ReconStatus, Long> m = new EnumMap<>(ReconStatus.class);
        m.put(ReconStatus.VERIFIED, verified); m.put(ReconStatus.MATCHED, matched); m.put(ReconStatus.PENDING, pending);
        m.put(ReconStatus.UNCONFIRMED, unconfirmed); m.put(ReconStatus.CONFLICT, conflict); m.put(ReconStatus.MISSING, missing);
        m.put(ReconStatus.DUPLICATE, duplicate);
        return m;
    }

    @Test @DisplayName("the quality score is verified over everything that is a fact about the portfolio — and says how")
    void score() {
        var s = scorer.score(counts(90, 5, 3, 1, 1, 0, 7), 0);
        assertThat(s.considered()).isEqualTo(100);          // duplicates excluded
        assertThat(s.percent()).isEqualTo(90.0);
        assertThat(s.formula()).contains("verified /");
    }

    @Test @DisplayName("an empty ledger has no score rather than a perfect one")
    void emptyLedger() {
        assertThat(scorer.score(counts(0, 0, 0, 0, 0, 0, 0), 0).scored()).isFalse();
    }

    @Test @DisplayName("an email-only ledger scores 0%, honestly")
    void emailOnly() {
        assertThat(scorer.score(counts(0, 0, 40, 0, 0, 0, 0), 0).percent()).isEqualTo(0.0);
    }
}
