package com.marketai.document.identity;

import com.marketai.document.identity.ExternalReference.ReferenceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EconomicIdentityResolverTest {

    private final EconomicIdentityResolver resolver = new EconomicIdentityResolver();

    private static final LocalDate DAY = LocalDate.of(2026, 9, 12);

    private static EconomicEvent trade(String amount, LocalDate date, ExternalReference... refs) {
        return new EconomicEvent("ZER-1234", "RELIANCE", "BUY",
            new BigDecimal("25"), new BigDecimal(amount), date, List.of(refs));
    }

    private static ExternalReference utr(String value) {
        return new ExternalReference(ReferenceType.UTR, value, "UTR No");
    }

    @Test
    @DisplayName("tier 1: a matching rail reference is decisive even when other fields differ")
    void railReferenceOverridesFieldDifferences() {
        // The same payment described by two documents: one quotes the settlement date and a
        // rounded amount, the other the trade date. A rail reference identifies exactly one
        // payment, so the field drift is irrelevant.
        EconomicEvent incoming = new EconomicEvent("ZER-1234", "RELIANCE", "BUY",
            new BigDecimal("25"), new BigDecimal("35332.50"), DAY.plusDays(2),
            List.of(utr("HDFCN52026091200123456")));

        MatchResult r = resolver.resolve(incoming,
            Map.of("evt-1", trade("35330.00", DAY, utr("HDFCN52026091200123456"))));

        assertThat(r.outcome()).isEqualTo(MatchOutcome.EXACT_REFERENCE);
        assertThat(r.score()).isEqualTo(100);
        assertThat(r.matchedId()).isEqualTo("evt-1");
        assertThat(r.isDuplicate()).isTrue();
    }

    @Test
    @DisplayName("a weak reference does NOT trigger tier 1")
    void issuerScopedReferencesDoNotMatchExactly() {
        // Two brokers can both mint order id "12345". Matching on that alone would merge
        // unrelated transactions.
        ExternalReference order = new ExternalReference(ReferenceType.ISSUER_REF, "ORD12345", "Order ID");
        EconomicEvent incoming = new EconomicEvent("BROKER-A", "INFY", "BUY",
            new BigDecimal("10"), new BigDecimal("15000.00"), DAY, List.of(order));

        MatchResult r = resolver.resolve(incoming, Map.of("evt-1",
            new EconomicEvent("BROKER-B", "TCS", "BUY",
                new BigDecimal("5"), new BigDecimal("20000.00"), DAY, List.of(order))));

        assertThat(r.outcome()).isNotEqualTo(MatchOutcome.EXACT_REFERENCE);
    }

    @Test
    @DisplayName("tier 2: an exact composite key matches without any reference")
    void exactCompositeKeyMatches() {
        MatchResult r = resolver.resolve(trade("35332.50", DAY),
            Map.of("evt-1", trade("35332.50", DAY)));

        assertThat(r.outcome()).isEqualTo(MatchOutcome.COMPOSITE_KEY);
        assertThat(r.isDuplicate()).isTrue();
    }

    @Test
    @DisplayName("paise rounding between two documents still matches, and is flagged")
    void toleranceAbsorbsRounding() {
        MatchResult r = resolver.resolve(trade("35332.50", DAY),
            Map.of("evt-1", trade("35332.00", DAY)));

        assertThat(r.outcome()).isEqualTo(MatchOutcome.TOLERANT);
        assertThat(r.isDuplicate()).isTrue();
        // Treated as a duplicate but visible, because a tolerance window decided it rather
        // than evidence.
        assertThat(r.outcome().warrantsFlag()).isTrue();
        assertThat(r.detail()).contains("within");
    }

    @Test
    @DisplayName("a settlement-vs-trade date gap still matches")
    void dateToleranceAbsorbsSettlementLag() {
        MatchResult r = resolver.resolve(trade("35332.50", DAY.plusDays(2)),
            Map.of("evt-1", trade("35332.50", DAY)));

        assertThat(r.isDuplicate()).isTrue();
        assertThat(r.detail()).contains("2d apart");
    }

    @Test
    @DisplayName("a BUY never matches a SELL, however identical everything else is")
    void oppositeDirectionsAreNeverMerged() {
        EconomicEvent sell = new EconomicEvent("ZER-1234", "RELIANCE", "SELL",
            new BigDecimal("25"), new BigDecimal("35332.50"), DAY, List.of());

        MatchResult r = resolver.resolve(sell, Map.of("evt-1", trade("35332.50", DAY)));

        // Same account, instrument, quantity, amount and date — and still not the same event.
        // Direction is a gate, not a weight, so agreement elsewhere cannot average it away.
        assertThat(r.outcome()).isEqualTo(MatchOutcome.NEW);
    }

    @Test
    @DisplayName("a genuinely different transaction is NEW, not a near-duplicate")
    void differentTransactionIsNew() {
        MatchResult r = resolver.resolve(trade("48000.00", DAY.plusDays(20)),
            Map.of("evt-1", trade("35332.50", DAY)));

        assertThat(r.outcome()).isEqualTo(MatchOutcome.NEW);
        assertThat(r.matchedId()).isNull();
    }

    @Test
    @DisplayName("an ambiguous case goes to a human rather than being resolved by score")
    void ambiguityRoutesToReview() {
        // Same instrument and date, amount off by more than tolerance. Booking it would risk a
        // double entry; merging it would risk discarding a real transaction. Neither is ours
        // to choose.
        MatchResult r = resolver.resolve(trade("35400.00", DAY),
            Map.of("evt-1", trade("35332.50", DAY)));

        assertThat(r.outcome()).isEqualTo(MatchOutcome.NEEDS_REVIEW);
        assertThat(r.isDuplicate()).isFalse();
        assertThat(r.outcome().warrantsFlag()).isTrue();
        assertThat(r.detail())
            .contains("Identical except for amount")
            .contains("charges included");
    }

    @Test
    @DisplayName("the charges-inclusive case is escalated by name, not by score")
    void amountOnlyDifferenceIsNamedNotScored() {
        // A bank debit of the gross amount against a contract note's net amount: the same
        // trade, differing by brokerage. Auto-importing would double-book it and corrupt the
        // holding via ledger replay; auto-merging could discard a real second trade.
        MatchResult r = resolver.resolve(trade("35352.50", DAY),
            Map.of("evt-1", trade("35332.50", DAY)));

        assertThat(r.outcome()).isEqualTo(MatchOutcome.NEEDS_REVIEW);
        assertThat(r.matchedId()).isEqualTo("evt-1");
    }

    @Test
    @DisplayName("a within-tolerance amount still matches rather than being escalated")
    void toleranceTakesPrecedenceOverTheAmbiguityRule() {
        // 50 paise apart is rounding, not a charges difference — the tolerant tier must claim
        // this before the amount-only rule escalates it.
        MatchResult r = resolver.resolve(trade("35333.00", DAY),
            Map.of("evt-1", trade("35332.50", DAY)));

        assertThat(r.outcome()).isEqualTo(MatchOutcome.TOLERANT);
        assertThat(r.isDuplicate()).isTrue();
    }

    @Test
    void firstEverEventIsNew() {
        assertThat(resolver.resolve(trade("100.00", DAY), Map.of()).outcome())
            .isEqualTo(MatchOutcome.NEW);
        assertThat(resolver.resolve(trade("100.00", DAY), null).outcome())
            .isEqualTo(MatchOutcome.NEW);
        assertThat(resolver.resolve(null, Map.of("e", trade("100.00", DAY))).outcome())
            .isEqualTo(MatchOutcome.NEW);
    }

    @Test
    @DisplayName("cash movements with no quantity are not penalised for lacking one")
    void absentQuantityOnBothSidesIsAgreement() {
        EconomicEvent a = new EconomicEvent("HDFC-9876", "SALARY", "CREDIT",
            null, new BigDecimal("120000.00"), DAY, List.of());
        EconomicEvent b = new EconomicEvent("HDFC-9876", "SALARY", "CREDIT",
            null, new BigDecimal("120000.00"), DAY, List.of());

        assertThat(resolver.resolve(a, Map.of("evt-1", b)).outcome())
            .isEqualTo(MatchOutcome.COMPOSITE_KEY);
    }

    @Test
    void accountAndInstrumentComparisonIgnoresCaseAndSpacing() {
        EconomicEvent messy = new EconomicEvent("  zer-1234 ", "reliance", "buy",
            new BigDecimal("25"), new BigDecimal("35332.50"), DAY, List.of());

        assertThat(resolver.resolve(messy, Map.of("evt-1", trade("35332.50", DAY))).isDuplicate())
            .isTrue();
    }

    @Test
    @DisplayName("the strongest available tier wins when several could match")
    void tiersAreTriedInOrder() {
        // A reference match exists on evt-2 while evt-1 would match on the composite key.
        // Tier 1 must win, because it is evidence rather than inference.
        EconomicEvent incoming = trade("35332.50", DAY, utr("SBIN226091200999"));

        MatchResult r = resolver.resolve(incoming, Map.of(
            "evt-1", trade("35332.50", DAY),
            "evt-2", trade("99999.00", DAY.minusDays(30), utr("SBIN226091200999"))));

        assertThat(r.outcome()).isEqualTo(MatchOutcome.EXACT_REFERENCE);
        assertThat(r.matchedId()).isEqualTo("evt-2");
    }
}
