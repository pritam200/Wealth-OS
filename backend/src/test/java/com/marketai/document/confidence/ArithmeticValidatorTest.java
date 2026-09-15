package com.marketai.document.confidence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ArithmeticValidatorTest {

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    @Test
    void aTradeThatReconcilesPasses() {
        ArithmeticValidator.Check c = ArithmeticValidator.tradeReconciles(
            bd("25"), bd("1412.50"), bd("20.00"), bd("35332.50"));

        assertThat(c.passed()).isTrue();
        assertThat(c.detail()).isNull();
    }

    @Test
    @DisplayName("a single misread digit breaks the identity and is caught")
    void misreadDigitIsCaught() {
        // 1,412.50 read as 1,472.50 — visually similar, arithmetically impossible.
        ArithmeticValidator.Check c = ArithmeticValidator.tradeReconciles(
            bd("25"), bd("1472.50"), bd("20.00"), bd("35332.50"));

        assertThat(c.passed()).isFalse();
        assertThat(c.detail()).contains("off by").contains("1500.00");
    }

    @Test
    @DisplayName("legitimate paise rounding is absorbed, a misread digit is not")
    void toleranceAbsorbsRoundingOnly() {
        assertThat(ArithmeticValidator.tradeReconciles(
            bd("3"), bd("333.333"), null, bd("1000.00")).passed()).isTrue();

        // One rupee out is within tolerance; ten is not.
        assertThat(ArithmeticValidator.tradeReconciles(
            bd("10"), bd("100.00"), null, bd("1001.00")).passed()).isTrue();
        assertThat(ArithmeticValidator.tradeReconciles(
            bd("10"), bd("100.00"), null, bd("1010.00")).passed()).isFalse();
    }

    @Test
    void missingInputsFailRatherThanPassVacuously() {
        // An unverifiable extraction must not be reported as verified.
        assertThat(ArithmeticValidator.tradeReconciles(null, bd("100"), null, bd("100")).passed()).isFalse();
        assertThat(ArithmeticValidator.tradeReconciles(bd("1"), null, null, bd("100")).passed()).isFalse();
        assertThat(ArithmeticValidator.tradeReconciles(bd("1"), bd("100"), null, null).passed()).isFalse();
    }

    @Test
    void statementBalanceReconciles() {
        assertThat(ArithmeticValidator.statementBalances(
            bd("10000.00"), bd("5000.00"), bd("2500.00"), bd("12500.00")).passed()).isTrue();

        ArithmeticValidator.Check bad = ArithmeticValidator.statementBalances(
            bd("10000.00"), bd("5000.00"), bd("2500.00"), bd("12000.00"));
        assertThat(bad.passed()).isFalse();
        assertThat(bad.detail()).contains("closing reads 12000.00");
    }

    @Test
    void mfUnitsReconcileAgainstAmount() {
        assertThat(ArithmeticValidator.mfReconciles(
            bd("123.456"), bd("81.0000"), bd("9999.94")).passed()).isTrue();

        assertThat(ArithmeticValidator.mfReconciles(
            bd("123.456"), bd("81.0000"), bd("8999.94")).passed()).isFalse();
    }
}
