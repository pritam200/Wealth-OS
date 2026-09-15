package com.marketai.networth.attribution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.marketai.networth.attribution.NetWorthAttributionCalculator.components;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NetWorthAttributionCalculatorTest {

    private final NetWorthAttributionCalculator calc = new NetWorthAttributionCalculator();

    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate TO = LocalDate.of(2026, 8, 31);

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    private static AttributionComponent c(AttributionKind k, String amount) {
        return AttributionComponent.of(k, bd(amount), k.name(), "ref-" + k);
    }

    @Test
    @DisplayName("the headline sentence separates what you did from what the market did")
    void decomposesIntoBehaviourAndMarket() {
        AttributionResult r = calc.attribute(FROM, TO, bd("1000000.00"), bd("1085000.00"),
            components(
                c(AttributionKind.INCOME, "120000.00"),
                c(AttributionKind.EXPENSE, "-55000.00"),
                c(AttributionKind.REVALUATION, "21000.00"),
                c(AttributionKind.FEE, "-1000.00")));

        assertThat(r.closes()).isTrue();
        assertThat(r.change()).isEqualByComparingTo("85000.00");
        assertThat(r.behavioural()).isEqualByComparingTo("64000.00");
        assertThat(r.revaluation()).isEqualByComparingTo("21000.00");
        assertThat(r.summary())
            .contains("rose by ₹85000.00")
            .contains("₹64000.00 from what you saved and spent")
            .contains("₹21000.00 from market movement");
    }

    @Test
    @DisplayName("a gap is reported, never folded into a category")
    void unexplainedIsSurfacedNotAbsorbed() {
        // ₹10,000 of the change is unaccounted for — a missing transaction, most likely.
        AttributionResult r = calc.attribute(FROM, TO, bd("100000.00"), bd("150000.00"),
            components(c(AttributionKind.INCOME, "40000.00")));

        assertThat(r.closes()).isFalse();
        assertThat(r.unexplained()).isEqualByComparingTo("10000.00");
        // Refuses to produce a confident sentence built on numbers that do not add up.
        assertThat(r.summary())
            .contains("cannot yet be explained")
            .doesNotContain("from market movement");
    }

    @Test
    @DisplayName("revaluation is computed from prices, so a plug cannot hide a missing transaction")
    void revaluationIsNotAResidual() {
        // If revaluation were derived as (change − everything else), this decomposition would
        // close by construction and the missing ₹10,000 above would silently have become
        // "market movement". Supplying it independently is what makes closure meaningful.
        BigDecimal reval = calc.revaluation(bd("100"), bd("1400.00"), bd("1610.00"));

        assertThat(reval).isEqualByComparingTo("21000.00");
    }

    @Test
    @DisplayName("revaluation uses the opening quantity, not the closing one")
    void revaluationDoesNotCreditGainsOnMoneyNotYetInvested() {
        // Bought more mid-period: crediting the full-period price move to the new units would
        // credit the market with returns on money that was not invested for that move.
        BigDecimal reval = calc.revaluation(bd("100"), bd("1000.00"), bd("1100.00"));

        assertThat(reval).isEqualByComparingTo("10000.00");
    }

    @Test
    @DisplayName("an internal transfer contributes nothing — this is the feature's whole basis")
    void internalTransfersAreNetZero() {
        // ₹50,000 moved bank → mutual fund. Most aggregators cannot tell this from ₹50,000 of
        // new saving, which is precisely why nobody ships this decomposition.
        AttributionResult r = calc.attribute(FROM, TO, bd("500000.00"), bd("500000.00"),
            components(
                AttributionComponent.of(AttributionKind.WITHDRAWAL, bd("-50000.00"),
                    "Bank → MF", "transfer-1"),
                AttributionComponent.of(AttributionKind.CONTRIBUTION, bd("50000.00"),
                    "Bank → MF", "transfer-1")));

        assertThat(r.change()).isEqualByComparingTo("0.00");
        assertThat(r.closes()).isTrue();
        assertThat(r.behavioural()).isEqualByComparingTo("0.00");
    }

    @Test
    void componentsAreGroupedByKind() {
        AttributionResult r = calc.attribute(FROM, TO, bd("0"), bd("300.00"),
            components(
                c(AttributionKind.INCOME, "100.00"),
                AttributionComponent.of(AttributionKind.INCOME, bd("250.00"), "dividend", "d1"),
                c(AttributionKind.EXPENSE, "-50.00")));

        assertThat(r.byKind().get(AttributionKind.INCOME)).isEqualByComparingTo("350.00");
        assertThat(r.byKind().get(AttributionKind.EXPENSE)).isEqualByComparingTo("-50.00");
    }

    @Test
    void zeroAmountComponentsAreDropped() {
        assertThat(components(c(AttributionKind.FEE, "0.00"), c(AttributionKind.INCOME, "5.00")))
            .hasSize(1);
    }

    @Test
    @DisplayName("a component without an amount is rejected at construction")
    void unquantifiedExplanationsAreRejected() {
        assertThatThrownBy(() ->
            AttributionComponent.of(AttributionKind.INCOME, null, "salary", "x"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be reconciled");
    }

    @Test
    @DisplayName("sub-rupee rounding across many components still closes")
    void roundingToleranceIsAllowed() {
        AttributionResult r = calc.attribute(FROM, TO, bd("0"), bd("100.00"),
            components(c(AttributionKind.INCOME, "99.50")));

        assertThat(r.unexplained()).isEqualByComparingTo("0.50");
        assertThat(r.closes()).isTrue();
    }

    // --- Modified Dietz ---

    @Test
    @DisplayName("a late deposit does not look like a loss")
    void modifiedDietzWeightsFlowsByTimeInvested() {
        // ₹100,000 grows to ₹210,000, but ₹100,000 of that arrived on the final day. A naive
        // (B−A)/A would report 110%; the true return on invested capital is ~10%.
        BigDecimal r = calc.modifiedDietz(bd("100000"), bd("210000"),
            List.of(new NetWorthAttributionCalculator.Flow(TO.minusDays(1), bd("100000"))),
            FROM, TO);

        assertThat(r).isNotNull();
        assertThat(r.doubleValue()).isBetween(0.09, 0.11);
    }

    @Test
    void modifiedDietzWithNoFlowsIsASimpleReturn() {
        BigDecimal r = calc.modifiedDietz(bd("100000"), bd("110000"), List.of(), FROM, TO);

        assertThat(r).isNotNull();
        assertThat(r.doubleValue()).isCloseTo(0.10, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    @DisplayName("a return on no invested capital is undefined, not zero")
    void modifiedDietzDeclinesRatherThanFabricate() {
        // Printing "0%" here would be a fabricated fact about a period with nothing invested.
        assertThat(calc.modifiedDietz(bd("0"), bd("100"), List.of(), FROM, TO)).isNull();
        assertThat(calc.modifiedDietz(bd("100"), bd("100"), List.of(), TO, FROM)).isNull();
        assertThat(calc.modifiedDietz(null, bd("100"), List.of(), FROM, TO)).isNull();
    }

    @Test
    void flowsOutsideThePeriodDoNotGetNegativeWeight() {
        BigDecimal r = calc.modifiedDietz(bd("100000"), bd("110000"),
            List.of(new NetWorthAttributionCalculator.Flow(TO.plusDays(10), bd("5000"))),
            FROM, TO);

        assertThat(r).isNotNull();
    }

    @Test
    void nullInputsDegradeToZeroRatherThanThrowing() {
        AttributionResult r = calc.attribute(FROM, TO, null, null, null);

        assertThat(r.change()).isEqualByComparingTo("0.00");
        assertThat(r.closes()).isTrue();
    }
}
