package com.marketai.tax.lot;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FifoLedgerTest {

    private static FifoLedger.Trade buy(String id, String date, String units, String price) {
        return new FifoLedger.Trade(id, LocalDate.parse(date), true, new BigDecimal(units), new BigDecimal(price));
    }

    private static FifoLedger.Trade sell(String id, String date, String units, String price) {
        return new FifoLedger.Trade(id, LocalDate.parse(date), false, new BigDecimal(units), new BigDecimal(price));
    }

    @Test
    void aSaleSpanningTwoLotsIsSplitShortAndLongTermByTheLotItConsumed() {
        FifoLedger.Result r = FifoLedger.replay(List.of(
            buy("b1", "2024-01-10", "10", "100"),
            buy("b2", "2026-03-01", "10", "150"),
            sell("s1", "2026-06-01", "15", "200")));

        // 10 units from the 2024 lot: long-term gain 1,000. 5 from the 2026 lot: short-term 250.
        assertThat(r.longTermGain(null, null)).isEqualByComparingTo("1000");
        assertThat(r.shortTermGain(null, null)).isEqualByComparingTo("250");
        assertThat(r.openLots()).singleElement().satisfies(l -> {
            assertThat(l.lotId()).isEqualTo("b2");
            assertThat(l.units()).isEqualByComparingTo("5");
        });
        assertThat(r.unmatchedUnits()).isEqualByComparingTo("0");
    }

    @Test
    void aUnitSoldOnItsFirstAnniversaryIsStillShortTerm() {
        assertThat(CapitalGainsRates.isLongTerm(LocalDate.of(2025, 6, 1), LocalDate.of(2026, 6, 1))).isFalse();
        assertThat(CapitalGainsRates.isLongTerm(LocalDate.of(2025, 6, 1), LocalDate.of(2026, 6, 2))).isTrue();
        assertThat(CapitalGainsRates.daysToLongTerm(LocalDate.of(2025, 6, 1), LocalDate.of(2026, 6, 1))).isEqualTo(1);
    }

    @Test
    void unitsSoldWithNoPurchaseOnRecordAreReportedNotGuessed() {
        FifoLedger.Result r = FifoLedger.replay(List.of(
            buy("b1", "2026-01-10", "5", "100"),
            sell("s1", "2026-02-01", "8", "120")));

        assertThat(r.shortTermGain(null, null)).isEqualByComparingTo("100");
        assertThat(r.unmatchedUnits()).isEqualByComparingTo("3");
    }

    @Test
    void gainsAreFilteredToTheSaleDateRange() {
        FifoLedger.Result r = FifoLedger.replay(List.of(
            buy("b1", "2026-01-10", "10", "100"),
            sell("s1", "2026-03-15", "5", "110"),
            sell("s2", "2026-04-15", "5", "130")));

        LocalDate fyFrom = LocalDate.of(2026, 4, 1), fyTo = LocalDate.of(2027, 3, 31);
        assertThat(r.shortTermGain(fyFrom, fyTo)).isEqualByComparingTo("150");
    }

    @Test
    void aSplitRescalesOpenLotsKeepingCostAndPurchaseDate() {
        FifoLedger.Result r = FifoLedger.replay(List.of(
            buy("b1", "2024-01-10", "10", "1000"),
            FifoLedger.Trade.split("sp", LocalDate.parse("2025-01-01"), new BigDecimal("5")),
            sell("s1", "2026-06-01", "50", "300")));

        // 10 @ ₹1,000 became 50 @ ₹200, still acquired in 2024: a long-term gain of 50 × 100.
        assertThat(r.longTermGain(null, null)).isEqualByComparingTo("5000");
        assertThat(r.shortTermGain(null, null)).isEqualByComparingTo("0");
        assertThat(r.unmatchedUnits()).isEqualByComparingTo("0");
    }

    @Test
    void chargesAddToCostAndComeOffProceeds() {
        var b = com.marketai.portfolio.entity.Transaction.builder().id(1L)
            .type(com.marketai.portfolio.entity.Transaction.TransactionType.BUY)
            .transactionDate(LocalDate.parse("2026-01-10")).quantity(new BigDecimal("10"))
            .price(new BigDecimal("100")).charges(new BigDecimal("20")).build();
        var s = com.marketai.portfolio.entity.Transaction.builder().id(2L)
            .type(com.marketai.portfolio.entity.Transaction.TransactionType.SELL)
            .transactionDate(LocalDate.parse("2026-03-10")).quantity(new BigDecimal("10"))
            .price(new BigDecimal("150")).charges(new BigDecimal("30")).build();

        FifoLedger.Result r = FifoLedger.replay(List.of(FifoLedger.Trade.of(b), FifoLedger.Trade.of(s)));

        // (1,500 − 30) − (1,000 + 20) = 450
        assertThat(r.shortTermGain(null, null)).isEqualByComparingTo("450");
    }
}
