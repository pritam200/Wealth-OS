package com.marketai.portfolio.service;

import com.marketai.portfolio.entity.Transaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A sale is checked against the units held on its own date, not against today's quantity. */
class DatedOversellTest {

    private static Transaction t(Transaction.TransactionType type, String date, String qty) {
        return Transaction.builder().type(type).transactionDate(LocalDate.parse(date))
            .quantity(new BigDecimal(qty)).price(BigDecimal.TEN).build();
    }

    @Test
    @DisplayName("selling 15 in February when only January's 10 were held is refused, though 20 are held today")
    void backdatedOversellIsRefused() {
        String shortfall = PortfolioService.firstOversell(List.of(
            t(Transaction.TransactionType.BUY, "2026-01-10", "10"),
            t(Transaction.TransactionType.SELL, "2026-02-10", "15"),
            t(Transaction.TransactionType.BUY, "2026-03-10", "10")));
        assertThat(shortfall).contains("2026-02-10", "only 10 units");
    }

    @Test
    @DisplayName("a same-day buy recorded first covers the sale; a split multiplies what is held")
    void coveredSalesPass() {
        assertThat(PortfolioService.firstOversell(List.of(
            t(Transaction.TransactionType.BUY, "2026-01-10", "10"),
            t(Transaction.TransactionType.SELL, "2026-01-10", "10")))).isNull();
        Transaction split = Transaction.builder().type(Transaction.TransactionType.SPLIT).transactionDate(LocalDate.parse("2026-02-01"))
            .quantity(BigDecimal.ZERO).price(BigDecimal.ZERO).ratioFrom(BigDecimal.ONE).ratioTo(new BigDecimal("2")).build();
        assertThat(PortfolioService.firstOversell(List.of(
            t(Transaction.TransactionType.BUY, "2026-01-10", "10"), split,
            t(Transaction.TransactionType.SELL, "2026-03-01", "20")))).isNull();
    }
}
