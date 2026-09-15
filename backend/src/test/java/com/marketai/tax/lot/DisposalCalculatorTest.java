package com.marketai.tax.lot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DisposalCalculatorTest {

    private final DisposalCalculator calc = new DisposalCalculator();

    private static final LocalDate SELL_DATE = LocalDate.of(2026, 9, 15);

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    private static TaxLot lot(String id, LocalDate acquired, String units, String cost) {
        return new TaxLot(id, acquired, bd(units), bd(cost), null);
    }

    // --- The rule most often got wrong ---

    @Test
    @DisplayName("the exemption is a per-FY aggregate, not applied afresh to each sale")
    void exemptionIsConsumedAcrossTheYear() {
        // ₹1,00,000 of the ₹1,25,000 exemption has already been used earlier in the year, so
        // only ₹25,000 remains. Applying the full exemption again — which is what a
        // per-redemption calculation does — would understate the tax by ₹12,500.
        FyExemptionLedger used = new FyExemptionLedger(2026, bd("100000"));

        DisposalCalculator.DisposalResult r = calc.sell(
            List.of(lot("L1", LocalDate.of(2024, 1, 1), "1000", "100")),
            bd("1000"), bd("200"), SELL_DATE, null, 0, used, null);

        assertThat(r.longTermGain()).isEqualByComparingTo("100000.00");
        assertThat(r.exemptionUsed()).isEqualByComparingTo("25000.00");

        // ₹75,000 taxable at 12.5% = ₹9,375, plus 4% cess = ₹9,750.
        assertThat(r.tax()).isEqualByComparingTo("9750.00");
    }

    @Test
    @DisplayName("a fresh year exempts the first ₹1,25,000 of long-term gain entirely")
    void freshYearExemptsFully() {
        DisposalCalculator.DisposalResult r = calc.sell(
            List.of(lot("L1", LocalDate.of(2024, 1, 1), "1000", "100")),
            bd("1000"), bd("200"), SELL_DATE, null, 0,
            FyExemptionLedger.empty(SELL_DATE), null);

        assertThat(r.exemptionUsed()).isEqualByComparingTo("100000.00");
        assertThat(r.tax()).isEqualByComparingTo("0.00");
    }

    @Test
    void theLedgerCarriesForwardAfterASale() {
        FyExemptionLedger after = calc.sell(
            List.of(lot("L1", LocalDate.of(2024, 1, 1), "1000", "100")),
            bd("1000"), bd("150"), SELL_DATE, null, 0,
            FyExemptionLedger.empty(SELL_DATE), null).ledgerAfter();

        assertThat(after.realisedLtcgToDate()).isEqualByComparingTo("50000.00");
        assertThat(after.remainingExemption()).isEqualByComparingTo("75000.00");
    }

    @Test
    void exemptionNeverGoesNegative() {
        assertThat(new FyExemptionLedger(2026, bd("500000")).remainingExemption())
            .isEqualByComparingTo("0");
    }

    @Test
    void financialYearRunsAprilToMarch() {
        assertThat(FyExemptionLedger.fyStartYearFor(LocalDate.of(2026, 4, 1))).isEqualTo(2026);
        assertThat(FyExemptionLedger.fyStartYearFor(LocalDate.of(2026, 3, 31))).isEqualTo(2025);
        assertThat(FyExemptionLedger.empty(LocalDate.of(2026, 9, 1)).label()).isEqualTo("FY 2026-27");
    }

    // --- Rates ---

    @Test
    @DisplayName("short-term equity gains are taxed at 20%, not the old 15%")
    void shortTermRateIsCurrent() {
        DisposalCalculator.DisposalResult r = calc.sell(
            List.of(lot("L1", LocalDate.of(2026, 6, 1), "1000", "100")),
            bd("1000"), bd("150"), SELL_DATE, null, 0,
            FyExemptionLedger.empty(SELL_DATE), null);

        assertThat(r.shortTermGain()).isEqualByComparingTo("50000.00");
        // No exemption applies to short-term gains at all — from rupee one.
        assertThat(r.exemptionUsed()).isEqualByComparingTo("0.00");
        // 50,000 × 20% = 10,000, plus 4% cess = 10,400.
        assertThat(r.tax()).isEqualByComparingTo("10400.00");
    }

    @Test
    void surchargeIsCappedAtFifteenPercent() {
        DisposalCalculator.DisposalResult capped = calc.sell(
            List.of(lot("L1", LocalDate.of(2024, 1, 1), "1000", "100")),
            bd("1000"), bd("400"), SELL_DATE, null, 0,
            FyExemptionLedger.empty(SELL_DATE), bd("0.37"));

        DisposalCalculator.DisposalResult atCap = calc.sell(
            List.of(lot("L1", LocalDate.of(2024, 1, 1), "1000", "100")),
            bd("1000"), bd("400"), SELL_DATE, null, 0,
            FyExemptionLedger.empty(SELL_DATE), bd("0.15"));

        assertThat(capped.tax()).isEqualByComparingTo(atCap.tax());
    }

    // --- Lots ---

    @Test
    @DisplayName("lots are consumed FIFO, so each parcel carries its own holding period")
    void fifoSplitsLongAndShortTerm() {
        DisposalCalculator.DisposalResult r = calc.sell(
            List.of(lot("OLD", LocalDate.of(2024, 1, 1), "600", "100"),
                    lot("NEW", LocalDate.of(2026, 6, 1), "600", "150")),
            bd("1000"), bd("200"), SELL_DATE, null, 0,
            FyExemptionLedger.empty(SELL_DATE), null);

        assertThat(r.lots()).hasSize(2);
        assertThat(r.lots().getFirst().lotId()).isEqualTo("OLD");
        assertThat(r.lots().getFirst().longTerm()).isTrue();
        assertThat(r.lots().get(1).longTerm()).isFalse();

        // 600 long-term units × ₹100 gain, 400 short-term × ₹50.
        assertThat(r.longTermGain()).isEqualByComparingTo("60000.00");
        assertThat(r.shortTermGain()).isEqualByComparingTo("20000.00");
    }

    @Test
    @DisplayName("grandfathering steps cost up to the 2018 value but cannot manufacture a loss")
    void grandfatheringIsCappedAtSalePrice() {
        TaxLot old = new TaxLot("G1", LocalDate.of(2015, 1, 1), bd("100"), bd("50"), bd("300"));

        // Sale at ₹200: cost steps up to ₹200, not ₹300 — the gain becomes nil, not negative.
        assertThat(old.effectiveCostPerUnit(bd("200"))).isEqualByComparingTo("200");
        // Sale at ₹400: full step-up to the 2018 value applies.
        assertThat(old.effectiveCostPerUnit(bd("400"))).isEqualByComparingTo("300");
    }

    @Test
    void grandfatheringDoesNotApplyToLaterPurchases() {
        TaxLot recent = new TaxLot("R1", LocalDate.of(2020, 1, 1), bd("100"), bd("50"), bd("300"));

        assertThat(recent.effectiveCostPerUnit(bd("400"))).isEqualByComparingTo("50");
    }

    // --- Exit load and net proceeds ---

    @Test
    @DisplayName("exit load reduces proceeds, and net-in-hand is what is reported")
    void netProceedsAreReportedNotGross() {
        DisposalCalculator.DisposalResult r = calc.sell(
            List.of(lot("L1", LocalDate.of(2026, 6, 1), "1000", "100")),
            bd("1000"), bd("150"), SELL_DATE, bd("0.01"), 365,
            FyExemptionLedger.empty(SELL_DATE), null);

        assertThat(r.grossProceeds()).isEqualByComparingTo("150000.00");
        assertThat(r.exitLoad()).isEqualByComparingTo("1500.00");
        assertThat(r.netProceeds())
            .isEqualByComparingTo(r.grossProceeds().subtract(r.exitLoad()).subtract(r.tax()));
        assertThat(r.totalDrag()).isEqualByComparingTo(r.exitLoad().add(r.tax()));
    }

    @Test
    void noExitLoadOnceTheWindowHasPassed() {
        DisposalCalculator.DisposalResult r = calc.sell(
            List.of(lot("L1", LocalDate.of(2024, 1, 1), "1000", "100")),
            bd("1000"), bd("150"), SELL_DATE, bd("0.01"), 365,
            FyExemptionLedger.empty(SELL_DATE), null);

        assertThat(r.exitLoad()).isEqualByComparingTo("0.00");
    }

    // --- The deferral prompt ---

    @Test
    @DisplayName("waiting for long-term status is quantified — nobody else ships this")
    void deferralAdviceQuantifiesTheSaving() {
        // Bought 15 Oct 2025, selling 15 Sep 2026 — one month short of long-term.
        DisposalCalculator.DisposalResult r = calc.sell(
            List.of(lot("L1", LocalDate.of(2025, 10, 15), "1000", "100")),
            bd("1000"), bd("150"), SELL_DATE, null, 0,
            FyExemptionLedger.empty(SELL_DATE), null);

        assertThat(r.deferralAdvice())
            .contains("2026-10-15")
            .contains("20% short-term rate")
            .contains("12.5% long-term rate")
            // 50,000 × (20% − 12.5%) = 3,750
            .contains("3750.00");
    }

    @Test
    void noDeferralAdviceWhenTheGainIsAlreadyLongTerm() {
        DisposalCalculator.DisposalResult r = calc.sell(
            List.of(lot("L1", LocalDate.of(2024, 1, 1), "1000", "100")),
            bd("1000"), bd("150"), SELL_DATE, null, 0,
            FyExemptionLedger.empty(SELL_DATE), null);

        assertThat(r.deferralAdvice()).isNull();
    }

    // --- Statutory renumbering ---

    @Test
    @DisplayName("section citations move on 1 April 2026 while the rates stay put")
    void sectionReferencesAreDataNotLiterals() {
        assertThat(CapitalGainsRates.shortTermSection(LocalDate.of(2026, 3, 31))).isEqualTo("111A");
        assertThat(CapitalGainsRates.shortTermSection(LocalDate.of(2026, 4, 1))).isEqualTo("196");
        assertThat(CapitalGainsRates.longTermSection(LocalDate.of(2026, 3, 31))).isEqualTo("112A");
        assertThat(CapitalGainsRates.longTermSection(LocalDate.of(2026, 4, 1))).isEqualTo("198");

        assertThat(CapitalGainsRates.LTCG_EXEMPTION).isEqualByComparingTo("125000");
    }
}
