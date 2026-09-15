package com.marketai.portfolio.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class XirrCalculatorTest {

    @Test
    void computesKnownXirr_singleInvestmentDoublingInOneYear() {
        // Invest 100,000 on day 0, worth 110,000 exactly one year later -> XIRR should be ~10%.
        java.util.List<XirrCalculator.CashFlow> flows = Arrays.asList(
            new XirrCalculator.CashFlow(LocalDate.of(2025, 1, 1), new BigDecimal("-100000")),
            new XirrCalculator.CashFlow(LocalDate.of(2026, 1, 1), new BigDecimal("110000")));

        Double xirr = XirrCalculator.computeXirrPercent(flows);

        assertThat(xirr).isNotNull();
        assertThat(xirr).isCloseTo(10.0, org.assertj.core.data.Offset.offset(0.5));
    }

    @Test
    void computesKnownXirr_multipleBuysAndAFinalValue() {
        // Two buys of 50,000 each six months apart, current value 115,000 one year after the
        // first buy — a real multi-cashflow case, not just a single in/out pair.
        java.util.List<XirrCalculator.CashFlow> flows = Arrays.asList(
            new XirrCalculator.CashFlow(LocalDate.of(2025, 1, 1), new BigDecimal("-50000")),
            new XirrCalculator.CashFlow(LocalDate.of(2025, 7, 1), new BigDecimal("-50000")),
            new XirrCalculator.CashFlow(LocalDate.of(2026, 1, 1), new BigDecimal("115000")));

        Double xirr = XirrCalculator.computeXirrPercent(flows);

        assertThat(xirr).isNotNull();
        assertThat(xirr).isGreaterThan(0);
    }

    @Test
    void returnsNullWhenFewerThanTwoFlows() {
        assertThat(XirrCalculator.computeXirrPercent(Collections.singletonList(
            new XirrCalculator.CashFlow(LocalDate.now(), new BigDecimal("-1000"))))).isNull();
        assertThat(XirrCalculator.computeXirrPercent(Collections.emptyList())).isNull();
        assertThat(XirrCalculator.computeXirrPercent(null)).isNull();
    }

    @Test
    void returnsNullWhenAllFlowsSameSign_noRealReturnToCompute() {
        java.util.List<XirrCalculator.CashFlow> flows = Arrays.asList(
            new XirrCalculator.CashFlow(LocalDate.of(2025, 1, 1), new BigDecimal("-50000")),
            new XirrCalculator.CashFlow(LocalDate.of(2025, 7, 1), new BigDecimal("-50000")));

        assertThat(XirrCalculator.computeXirrPercent(flows)).isNull();
    }

    @Test
    void handlesALoss_negativeXirr() {
        java.util.List<XirrCalculator.CashFlow> flows = Arrays.asList(
            new XirrCalculator.CashFlow(LocalDate.of(2025, 1, 1), new BigDecimal("-100000")),
            new XirrCalculator.CashFlow(LocalDate.of(2026, 1, 1), new BigDecimal("80000")));

        Double xirr = XirrCalculator.computeXirrPercent(flows);

        assertThat(xirr).isNotNull();
        assertThat(xirr).isLessThan(0);
    }
}
