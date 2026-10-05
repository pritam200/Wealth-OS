package com.marketai.dataplatform.pipeline;

import com.marketai.dataplatform.domain.SourceType;
import com.marketai.dataplatform.domain.TransactionType;
import com.marketai.dataplatform.pipeline.TransactionMatcher.Decision;
import com.marketai.dataplatform.pipeline.TransactionMatcher.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.marketai.dataplatform.pipeline.TestTxns.*;
import static org.assertj.core.api.Assertions.assertThat;

class TransactionMatcherTest {

    private final TransactionMatcher matcher = new TransactionMatcher();

    @Test @DisplayName("an email SIP and the institution's BUY of the same asset, date and amount are one transaction")
    void emailThenAggregatorMatch() {
        var existing = entry(1, 10, TransactionType.SIP, "2026-08-15", "10000.00", SourceType.EMAIL, "msg-1");
        var incoming = txn(SourceType.ACCOUNT_AGGREGATOR, TransactionType.BUY, "2026-08-15", "10000.00").sourceReference("AA-77").build();
        Decision d = matcher.decide(incoming, 10L, 1L, true, List.of(existing));
        assertThat(d.kind()).isEqualTo(Kind.COMPOSITE_EXACT);
        assertThat(d.matchedId()).isEqualTo(1L);
        assertThat(d.explanation()).anyMatch(s -> s.contains("same asset"));
    }

    @Test @DisplayName("the same ₹10,000 SIP on the same day in two different funds is two transactions")
    void sameAmountDifferentFundIsNotMerged() {
        var fundA = entry(1, 10, TransactionType.SIP, "2026-08-15", "10000.00", SourceType.EMAIL, "msg-1");
        var incomingFundB = txn(SourceType.ACCOUNT_AGGREGATOR, TransactionType.SIP, "2026-08-15", "10000.00").build();
        Decision d = matcher.decide(incomingFundB, 11L, 1L, true, List.of(fundA));
        assertThat(d.kind()).isEqualTo(Kind.NONE);
        assertThat(d.merges()).isFalse();
    }

    @Test @DisplayName("equal amounts alone never merge: a different date beyond tolerance is a different transaction")
    void differentDateNotMerged() {
        var existing = entry(1, 10, TransactionType.SIP, "2026-07-15", "10000.00", SourceType.EMAIL, "m1");
        var incoming = txn(SourceType.ACCOUNT_AGGREGATOR, TransactionType.SIP, "2026-08-15", "10000.00").build();
        assertThat(matcher.decide(incoming, 10L, 1L, true, List.of(existing)).kind()).isEqualTo(Kind.NONE);
    }

    @Test @DisplayName("a different kind of event (purchase vs sale) never matches")
    void differentFamily() {
        var existing = entry(1, 10, TransactionType.SELL, "2026-08-15", "10000.00", SourceType.EMAIL, "m1");
        var incoming = txn(SourceType.ACCOUNT_AGGREGATOR, TransactionType.BUY, "2026-08-15", "10000.00").build();
        assertThat(matcher.decide(incoming, 10L, 1L, true, List.of(existing)).kind()).isEqualTo(Kind.NONE);
    }

    @Test @DisplayName("an unidentified asset can never be matched")
    void unidentifiedAsset() {
        var existing = entry(1, 10, TransactionType.SIP, "2026-08-15", "10000.00", SourceType.EMAIL, "m1");
        var incoming = txn(SourceType.STATEMENT, TransactionType.SIP, "2026-08-15", "10000.00").asset(null).build();
        Decision d = matcher.decide(incoming, null, 1L, true, List.of(existing));
        assertThat(d.kind()).isEqualTo(Kind.NONE);
        assertThat(d.explanationText()).contains("asset could not be identified");
    }

    @Test @DisplayName("a shared reference across sources is the same event when asset and kind agree, even if figures differ — flagged as a conflict")
    void referenceMatchWithConflict() {
        var existing = entry(1, 10, TransactionType.BUY, "2026-08-15", "10000.00", SourceType.EMAIL, "UTR123");
        var incoming = txn(SourceType.ACCOUNT_AGGREGATOR, TransactionType.BUY, "2026-08-15", "12500.00").sourceReference("utr123").build();
        Decision d = matcher.decide(incoming, 10L, 1L, true, List.of(existing));
        assertThat(d.kind()).isEqualTo(Kind.EXACT_REFERENCE);
        assertThat(d.materialConflict()).isTrue();
    }

    @Test @DisplayName("a shared reference on a different asset is not trusted")
    void referenceOnDifferentAsset() {
        var existing = entry(1, 10, TransactionType.BUY, "2026-08-15", "10000.00", SourceType.EMAIL, "123456");
        var incoming = txn(SourceType.ACCOUNT_AGGREGATOR, TransactionType.BUY, "2026-08-15", "10000.00").sourceReference("123456").build();
        Decision d = matcher.decide(incoming, 99L, 1L, true, List.of(existing));
        assertThat(d.merges()).isFalse();
        assertThat(d.explanationText()).contains("shared but the asset");
    }

    @Test @DisplayName("the same source delivering the same reference again is a redelivery")
    void redelivery() {
        var existing = entry(1, 10, TransactionType.BUY, "2026-08-15", "10000.00", SourceType.ACCOUNT_AGGREGATOR, "AA-1");
        var incoming = txn(SourceType.ACCOUNT_AGGREGATOR, TransactionType.BUY, "2026-08-15", "10000.00").sourceReference("AA-1").build();
        assertThat(matcher.decide(incoming, 10L, 1L, true, List.of(existing)).kind()).isEqualTo(Kind.SAME_SOURCE_REDELIVERY);
    }

    @Test @DisplayName("same source, same asset/date/amount, no references to tell them apart: not merged, flagged as a possible duplicate")
    void sameSourceRivalIsFlaggedNotMerged() {
        var existing = entry(1, 10, TransactionType.SIP, "2026-08-15", "10000.00", SourceType.EMAIL, null);
        var incoming = txn(SourceType.EMAIL, TransactionType.SIP, "2026-08-15", "10000.00").build();
        Decision d = matcher.decide(incoming, 10L, 1L, true, List.of(existing));
        assertThat(d.kind()).isEqualTo(Kind.NONE);
        assertThat(d.possibleDuplicate()).isTrue();
        assertThat(d.relatedIds()).containsExactly(1L);
    }

    @Test @DisplayName("one source's differing references prove two transactions: two identical partial fills with distinct trade numbers")
    void differingReferencesFromOneSourceAreTwoTrades() {
        var first = entry(1, 10, TransactionType.BUY, "2026-08-15", "5000.00", SourceType.BROKER_API, "T-1001");
        var second = txn(SourceType.BROKER_API, TransactionType.BUY, "2026-08-15", "5000.00").sourceReference("T-1002").build();
        Decision d = matcher.decide(second, 10L, 1L, true, List.of(first));
        assertThat(d.kind()).isEqualTo(Kind.NONE);
        assertThat(d.possibleDuplicate()).isFalse();
    }

    @Test @DisplayName("two equally good candidates are ambiguous and are not merged")
    void ambiguous() {
        var a = entry(1, 10, TransactionType.SIP, "2026-08-15", "10000.00", SourceType.EMAIL, "m1");
        var b = entry(2, 10, TransactionType.SIP, "2026-08-15", "10000.00", SourceType.MANUAL, "m2");
        var incoming = txn(SourceType.ACCOUNT_AGGREGATOR, TransactionType.SIP, "2026-08-15", "10000.00").build();
        Decision d = matcher.decide(incoming, 10L, 1L, true, List.of(a, b));
        assertThat(d.kind()).isEqualTo(Kind.AMBIGUOUS);
        assertThat(d.relatedIds()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test @DisplayName("a settlement-date offset of a day matches only when quantity and amount agree exactly")
    void dateToleranceNeedsExactFigures() {
        var existing = new LedgerEntryView(1L, 1L, true, 10L, TransactionType.BUY, com.marketai.dataplatform.domain.TxnStatus.CONFIRMED,
            LocalDate.parse("2026-08-14"), new BigDecimal("52.431"), new BigDecimal("10000.00"), new BigDecimal("10000.00"), null, null,
            List.of(new LedgerEntryView.Source(SourceType.EMAIL, "email", "m1")));
        var exact = txn(SourceType.STATEMENT, TransactionType.BUY, "2026-08-15", "10000.00").quantity(new BigDecimal("52.431")).build();
        assertThat(matcher.decide(exact, 10L, 1L, true, List.of(existing)).kind()).isEqualTo(Kind.COMPOSITE_TOLERANT);
        var off = txn(SourceType.STATEMENT, TransactionType.BUY, "2026-08-15", "10040.00").quantity(new BigDecimal("52.431")).build();
        assertThat(matcher.decide(off, 10L, 1L, true, List.of(existing)).kind()).isEqualTo(Kind.NONE);
    }

    @Test @DisplayName("rounding of a rupee between sources still matches")
    void paiseRounding() {
        var existing = entry(1, 10, TransactionType.SIP, "2026-08-15", "10000.00", SourceType.EMAIL, "m1");
        var incoming = txn(SourceType.ACCOUNT_AGGREGATOR, TransactionType.SIP, "2026-08-15", "10000.50").build();
        assertThat(matcher.decide(incoming, 10L, 1L, true, List.of(existing)).kind()).isEqualTo(Kind.COMPOSITE_EXACT);
    }

    @Test @DisplayName("an email naming only the institution matches the specific account's transaction")
    void placeholderAccountMatchesSpecific() {
        var placeholderEntry = new LedgerEntryView(1L, 5L, false, 10L, TransactionType.SIP, com.marketai.dataplatform.domain.TxnStatus.PENDING_RECONCILIATION,
            LocalDate.parse("2026-08-15"), null, new BigDecimal("10000"), new BigDecimal("10000"), null, null,
            List.of(new LedgerEntryView.Source(SourceType.EMAIL, "email", "m1")));
        var incoming = txn(SourceType.ACCOUNT_AGGREGATOR, TransactionType.SIP, "2026-08-15", "10000").build();
        assertThat(matcher.decide(incoming, 10L, 9L, true, List.of(placeholderEntry)).merges()).isTrue();
    }

    @Test @DisplayName("two different specific accounts never match")
    void differentSpecificAccounts() {
        var other = new LedgerEntryView(1L, 5L, true, 10L, TransactionType.SIP, com.marketai.dataplatform.domain.TxnStatus.PENDING_RECONCILIATION,
            LocalDate.parse("2026-08-15"), null, new BigDecimal("10000"), new BigDecimal("10000"), null, null,
            List.of(new LedgerEntryView.Source(SourceType.EMAIL, "email", "m1")));
        var incoming = txn(SourceType.ACCOUNT_AGGREGATOR, TransactionType.SIP, "2026-08-15", "10000").build();
        assertThat(matcher.decide(incoming, 10L, 9L, true, List.of(other)).kind()).isEqualTo(Kind.NONE);
    }

    @Test @DisplayName("rejected and reversed entries are not candidates")
    void ignoresNonCounting() {
        var rejected = new LedgerEntryView(1L, 1L, true, 10L, TransactionType.SIP, com.marketai.dataplatform.domain.TxnStatus.REJECTED,
            LocalDate.parse("2026-08-15"), null, new BigDecimal("10000"), new BigDecimal("10000"), null, null,
            List.of(new LedgerEntryView.Source(SourceType.EMAIL, "email", "m1")));
        var incoming = txn(SourceType.ACCOUNT_AGGREGATOR, TransactionType.SIP, "2026-08-15", "10000").build();
        assertThat(matcher.decide(incoming, 10L, 1L, true, List.of(rejected)).kind()).isEqualTo(Kind.NONE);
    }

    @Test @DisplayName("a stock split matches by asset, date and ratio, not by amount")
    void corporateActionRatio() {
        var existing = new LedgerEntryView(1L, 1L, true, 10L, TransactionType.SPLIT, com.marketai.dataplatform.domain.TxnStatus.PENDING_RECONCILIATION,
            LocalDate.parse("2026-08-15"), null, null, null, BigDecimal.ONE, new BigDecimal("5"),
            List.of(new LedgerEntryView.Source(SourceType.EMAIL, "email", "m1")));
        var same = txn(SourceType.BROKER_API, TransactionType.SPLIT, "2026-08-15", "0").grossAmount(null).netAmount(null)
            .ratioFrom(BigDecimal.ONE).ratioTo(new BigDecimal("5")).build();
        assertThat(matcher.decide(same, 10L, 1L, true, List.of(existing)).merges()).isTrue();
        var different = same.toBuilder().ratioTo(new BigDecimal("10")).build();
        assertThat(matcher.decide(different, 10L, 1L, true, List.of(existing)).merges()).isFalse();
    }

    @Test @DisplayName("the two legs of a switch are different assets and never merge")
    void switchLegs() {
        var out = entry(1, 10, TransactionType.SWITCH_OUT, "2026-08-15", "10000.00", SourceType.EMAIL, "m1");
        var inLeg = txn(SourceType.EMAIL, TransactionType.SWITCH_IN, "2026-08-15", "10000.00").build();
        assertThat(matcher.decide(inLeg, 11L, 1L, true, List.of(out)).merges()).isFalse();
    }
}
