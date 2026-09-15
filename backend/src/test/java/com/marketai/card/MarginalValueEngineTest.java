package com.marketai.card;

import com.marketai.card.counter.MarginalValueEngine;
import com.marketai.card.counter.SpendPurpose;
import com.marketai.card.counter.SpendWindow;
import com.marketai.card.counter.UtilizationCounter;
import com.marketai.card.route.CardNetwork;
import com.marketai.card.route.PaymentRoute;
import com.marketai.card.route.RouteEligibility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class MarginalValueEngineTest {

    private final MarginalValueEngine engine = new MarginalValueEngine();

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    private static UtilizationCounter cap(String consumed, String limit) {
        return new UtilizationCounter("card-1", "cap-1", SpendWindow.CALENDAR_MONTH,
            SpendPurpose.REWARD, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31),
            bd(consumed), bd(limit), UtilizationCounter.Source.DERIVED);
    }

    private static UtilizationCounter feeWaiver(String consumed, String limit) {
        return new UtilizationCounter("card-1", "waiver", SpendWindow.ANNIVERSARY_YEAR,
            SpendPurpose.FEE_WAIVER, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            bd(consumed), bd(limit), UtilizationCounter.Source.DERIVED);
    }

    // --- UPI / RuPay correctness gate ---

    @Test
    @DisplayName("a Visa card on UPI is impossible, not merely worse")
    void nonRupayCardsAreIneligibleOnUpi() {
        MarginalValueEngine.Valuation v = engine.value(
            CardNetwork.VISA, PaymentRoute.UPI_P2M, "5411", "P2M", bd("5000"),
            bd("0.05"), bd("0.01"), null, null, null, null);

        assertThat(v.eligible()).isFalse();
        assertThat(v.ineligibleReason()).contains("only RuPay credit cards can");
        assertThat(v.total()).isEqualByComparingTo("0");
    }

    @Test
    void rupayOnUpiIsEligible() {
        assertThat(engine.value(CardNetwork.RUPAY, PaymentRoute.UPI_P2M, "5411", "P2M",
            bd("5000"), bd("0.05"), bd("0.01"), null, null, null, null).eligible()).isTrue();
    }

    @Test
    void theSameVisaCardIsFineOffUpi() {
        assertThat(engine.value(CardNetwork.VISA, PaymentRoute.CARD_NUMBER, "5411", "P2M",
            bd("5000"), bd("0.05"), bd("0.01"), null, null, null, null).eligible()).isTrue();
    }

    @Test
    @DisplayName("NPCI-blocked transaction types are refused even on RuPay")
    void blockedTransactionTypesAreRefused() {
        for (String blocked : new String[]{"P2P", "C2C", "MUTUAL_FUND", "IPO", "ATM_WITHDRAWAL"}) {
            RouteEligibility.Verdict v = RouteEligibility.check(
                CardNetwork.RUPAY, PaymentRoute.UPI_P2M, "5411", blocked, bd("1000"));
            assertThat(v.eligible()).as(blocked).isFalse();
        }
    }

    @Test
    void acquirerBlockedMccsAreRefused() {
        assertThat(RouteEligibility.check(CardNetwork.RUPAY, PaymentRoute.UPI_P2M,
            "6011", "P2M", bd("1000")).eligible()).isFalse();
        assertThat(RouteEligibility.check(CardNetwork.RUPAY, PaymentRoute.UPI_P2M,
            "7995", "P2M", bd("1000")).eligible()).isFalse();
    }

    @Test
    void theUpiPerTransactionLimitIsEnforced() {
        assertThat(RouteEligibility.check(CardNetwork.RUPAY, PaymentRoute.UPI_P2M,
            "5411", "P2M", bd("600000")).eligible()).isFalse();
    }

    // --- Marginal value ---

    @Test
    @DisplayName("spend past the cap earns the base rate, not the headline rate")
    void capExhaustionChangesTheMarginalRate() {
        // ₹8,000 of headroom left, spending ₹10,000: the first ₹8,000 earns 5%, the rest 1%.
        // A rate table would have claimed ₹500.
        MarginalValueEngine.Valuation v = engine.value(
            CardNetwork.VISA, PaymentRoute.CARD_NUMBER, "5411", "P2M", bd("10000"),
            bd("0.05"), bd("0.01"), cap("2000", "10000"), null, null, null);

        assertThat(v.rewardValue()).isEqualByComparingTo("420.00");  // 8000*.05 + 2000*.01
        assertThat(v.explanation()).contains("past the cap");
    }

    @Test
    @DisplayName("a 0%-reward card can be the right answer when it closes a fee waiver")
    void feeWaiverCanOutweighZeroRewards() {
        // The counter-intuitive case the whole engine exists for: caps exhausted, so rewards are
        // nil, but ₹40,000 of spend closes a ₹40,000 gap and avoids a ₹2,000 annual fee.
        MarginalValueEngine.Valuation v = engine.value(
            CardNetwork.VISA, PaymentRoute.CARD_NUMBER, "5411", "P2M", bd("40000"),
            bd("0.05"), BigDecimal.ZERO,
            cap("10000", "10000"),                 // fully exhausted
            feeWaiver("160000", "200000"), bd("2000"), null);

        assertThat(v.rewardValue()).isEqualByComparingTo("0.00");
        assertThat(v.feeWaiverValue()).isEqualByComparingTo("2000.00");
        assertThat(v.total()).isEqualByComparingTo("2000.00");
        assertThat(v.explanation()).contains("annual-fee waiver progress");
    }

    @Test
    @DisplayName("partial fee-waiver progress is worth a proportional share of the fee")
    void partialWaiverProgressIsProRated() {
        MarginalValueEngine.Valuation v = engine.value(
            CardNetwork.VISA, PaymentRoute.CARD_NUMBER, "5411", "P2M", bd("20000"),
            BigDecimal.ZERO, BigDecimal.ZERO, null,
            feeWaiver("160000", "200000"), bd("2000"), null);

        // ₹20,000 of a ₹40,000 gap = half the ₹2,000 fee.
        assertThat(v.feeWaiverValue()).isEqualByComparingTo("1000.00");
    }

    @Test
    void anAlreadyWaivedFeeIsWorthNothingMore() {
        MarginalValueEngine.Valuation v = engine.value(
            CardNetwork.VISA, PaymentRoute.CARD_NUMBER, "5411", "P2M", bd("10000"),
            bd("0.01"), bd("0.01"), null,
            feeWaiver("200000", "200000"), bd("2000"), null);

        assertThat(v.feeWaiverValue()).isEqualByComparingTo("0");
    }

    @Test
    void surchargeIsDeductedFromTheTotal() {
        MarginalValueEngine.Valuation v = engine.value(
            CardNetwork.VISA, PaymentRoute.CARD_NUMBER, "6513", "P2M", bd("10000"),
            bd("0.01"), bd("0.01"), null, null, null, bd("0.01"));

        assertThat(v.surcharge()).isEqualByComparingTo("100.00");
        assertThat(v.total()).isEqualByComparingTo("0.00");   // 100 earned, 100 charged
    }

    // --- Counter mechanics ---

    @Test
    void counterArithmetic() {
        UtilizationCounter c = cap("7500", "10000");

        assertThat(c.remaining()).isEqualByComparingTo("2500");
        assertThat(c.isExhausted()).isFalse();
        assertThat(c.acceleratedPortion(bd("5000"))).isEqualByComparingTo("2500");
        assertThat(c.acceleratedPortion(bd("1000"))).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("over-consumption clamps to zero headroom rather than going negative")
    void overConsumedCounterHasNoHeadroom() {
        UtilizationCounter c = cap("12000", "10000");

        assertThat(c.remaining()).isEqualByComparingTo("0");
        assertThat(c.isExhausted()).isTrue();
        assertThat(c.acceleratedPortion(bd("5000"))).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("statement-cycle and calendar-month windows are distinct by design")
    void windowTypesAreDistinct() {
        // SBI measures against the statement cycle, HDFC against the calendar month. Conflating
        // them resets a counter on the wrong day and recommends a card whose cap is already full.
        assertThat(SpendWindow.values())
            .contains(SpendWindow.CALENDAR_MONTH, SpendWindow.STATEMENT_CYCLE);
    }

    @Test
    @DisplayName("purposes are separate because issuers maintain separate exclusion lists")
    void spendPurposesAreSeparate() {
        // ICICI excludes rent, government and education from fee-waiver spend specifically while
        // counting them for rewards — so the same ₹10,000 both earns and does not count.
        assertThat(SpendPurpose.values()).contains(
            SpendPurpose.REWARD, SpendPurpose.MILESTONE,
            SpendPurpose.FEE_WAIVER, SpendPurpose.SPECIFIC_BENEFIT);
    }
}
