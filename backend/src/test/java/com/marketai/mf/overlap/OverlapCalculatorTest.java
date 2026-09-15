package com.marketai.mf.overlap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OverlapCalculatorTest {

    private final OverlapCalculator calc = new OverlapCalculator();

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 31);
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 15);

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    private static SchemeHolding h(String isin, String name, String pct) {
        return new SchemeHolding(isin, name, "Financials", bd("1000"), bd(pct));
    }

    @Test
    @DisplayName("overlap is the sum of the smaller weight of each shared stock")
    void sumOfMinimums() {
        // HDFC Bank 5 vs 8 contributes 5; Reliance 7 vs 4 contributes 4; Infosys is unique.
        List<SchemeHolding> a = List.of(
            h("INE040A01034", "HDFC Bank", "5.0"),
            h("INE002A01018", "Reliance", "7.0"),
            h("INE009A01021", "Infosys", "6.0"));
        List<SchemeHolding> b = List.of(
            h("INE040A01034", "HDFC Bank", "8.0"),
            h("INE002A01018", "Reliance", "4.0"),
            h("INE467B01029", "TCS", "9.0"));

        OverlapResult r = calc.overlap(a, b, AS_OF, TODAY);

        assertThat(r.overlapPercent()).isEqualByComparingTo("9.00");
        assertThat(r.commonHoldings()).hasSize(2);
    }

    @Test
    @DisplayName("identical portfolios overlap completely")
    void identicalPortfolios() {
        List<SchemeHolding> a = List.of(h("INE040A01034", "HDFC Bank", "60.0"),
                                        h("INE002A01018", "Reliance", "40.0"));

        assertThat(calc.overlap(a, a, AS_OF, TODAY).overlapPercent())
            .isEqualByComparingTo("100.00");
    }

    @Test
    void disjointPortfoliosOverlapNotAtAll() {
        assertThat(calc.overlap(
            List.of(h("INE001", "A", "50.0")),
            List.of(h("INE002", "B", "50.0")), AS_OF, TODAY).overlapPercent())
            .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("matching is on ISIN, because the same company is named differently per AMC")
    void isinIsTheJoinKey() {
        // Name matching would miss this pair and under-report overlap — silently, and in the
        // direction that makes the portfolio look better diversified than it is.
        OverlapResult r = calc.overlap(
            List.of(h("INE040A01034", "HDFC Bank Ltd", "10.0")),
            List.of(h("ine040a01034", "HDFC BANK LIMITED", "10.0")),
            AS_OF, TODAY);

        assertThat(r.overlapPercent()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("cash in both funds caps overlap below 100, and that is reported not normalised")
    void cashDragIsNotNormalisedAway() {
        // Two funds each 8% in cash can never exceed 92% overlap. The raw figure reflects real
        // rupee duplication, which is the question the user is actually asking.
        List<SchemeHolding> a = List.of(h("INE001", "A", "46.0"), h("INE002", "B", "46.0"));
        List<SchemeHolding> b = List.of(h("INE001", "A", "46.0"), h("INE002", "B", "46.0"));

        assertThat(calc.overlap(a, b, AS_OF, TODAY).overlapPercent())
            .isEqualByComparingTo("92.00");
    }

    @Test
    @DisplayName("stale disclosures refuse to produce a number")
    void staleDataDeclines() {
        // A fund that has exited a stock would still show exposure to it.
        OverlapResult r = calc.overlap(
            List.of(h("INE001", "A", "50.0")), List.of(h("INE001", "A", "50.0")),
            LocalDate.of(2026, 5, 1), TODAY);

        assertThat(r.isStale()).isTrue();
        assertThat(r.summary()).contains("not shown").contains("already exited");
    }

    @Test
    @DisplayName("the methodology is published with the result")
    void methodologyIsStated() {
        OverlapResult r = calc.overlap(
            List.of(h("INE001", "A", "50.0")), List.of(h("INE001", "A", "50.0")), AS_OF, TODAY);

        // Advisorkhoj reports a count of shared stocks with no stated methodology, so our number
        // will legitimately differ from theirs. Saying which definition we use pre-empts the
        // conclusion that one of us is broken.
        assertThat(r.summary()).contains("as of 2026-08-31");
        assertThat(r.methodology())
            .contains("sum of the smaller weight")
            .contains("count shared stocks instead will report a different number");
    }

    @Test
    void topContributorsIdentifyWhereTheDuplicationIs() {
        OverlapResult r = calc.overlap(
            List.of(h("INE001", "Big", "20.0"), h("INE002", "Small", "2.0")),
            List.of(h("INE001", "Big", "25.0"), h("INE002", "Small", "3.0")),
            AS_OF, TODAY);

        assertThat(r.topContributors(1)).hasSize(1);
        assertThat(r.topContributors(1).getFirst().name()).isEqualTo("Big");
    }

    // --- Look-through ---

    @Test
    @DisplayName("effective exposure joins direct holdings to fund look-through")
    void effectiveExposureCombinesDirectAndFunds() {
        // The case no Indian tool covers: Tickertape compares funds to funds, smallcase has no
        // overlap tooling, and nobody joins directly-held equity to fund holdings.
        BigDecimal exposure = calc.effectiveExposure(
            "INE040A01034",
            bd("50000"),                                    // held directly
            Map.of("SCHEME-A", bd("200000"),                // ₹2L in fund A
                   "SCHEME-B", bd("100000")),               // ₹1L in fund B
            Map.of("SCHEME-A", List.of(h("INE040A01034", "HDFC Bank", "8.0")),
                   "SCHEME-B", List.of(h("INE040A01034", "HDFC Bank", "5.0"))));

        // 50,000 direct + 8% of 2,00,000 + 5% of 1,00,000 = 50,000 + 16,000 + 5,000
        assertThat(exposure).isEqualByComparingTo("71000.00");
    }

    @Test
    void aStockHeldNowhereHasOnlyItsDirectValue() {
        assertThat(calc.effectiveExposure("INE999", bd("1000"),
            Map.of("S", bd("100000")),
            Map.of("S", List.of(h("INE040A01034", "HDFC Bank", "8.0")))))
            .isEqualByComparingTo("1000.00");
    }

    @Test
    void nullsDegradeSafely() {
        assertThat(calc.overlap(null, null, AS_OF, TODAY).overlapPercent())
            .isEqualByComparingTo("0.00");
        assertThat(calc.effectiveExposure(null, bd("100"), null, null))
            .isEqualByComparingTo("100");
    }
}
