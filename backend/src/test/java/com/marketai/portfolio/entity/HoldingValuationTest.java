package com.marketai.portfolio.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class HoldingValuationTest {

    private static Holding holding(String symbol, String price, LocalDate asOf) {
        return Holding.builder().symbol(symbol).name(symbol).quantity(new BigDecimal("10"))
            .averageCost(new BigDecimal("100")).currentPrice(price == null ? null : new BigDecimal(price))
            .priceAsOf(asOf).build();
    }

    @Test
    void aZeroPriceIsTreatedAsMissingAndTheHoldingIsCarriedAtCost() {
        Holding h = holding("X.NS", "0", LocalDate.of(2026, 9, 28));
        assertThat(h.valuationBasis(LocalDate.of(2026, 9, 29))).isEqualTo(Holding.ValuationBasis.COST);
        assertThat(h.getCurrentValue()).isEqualByComparingTo("1000");
    }

    @Test
    void stalenessWindowIsLongerForFundsThanForStocks() {
        LocalDate today = LocalDate.of(2026, 9, 29);
        LocalDate fiveDaysAgo = today.minusDays(5);
        assertThat(holding("X.NS", "120", fiveDaysAgo).valuationBasis(today)).isEqualTo(Holding.ValuationBasis.STALE);
        assertThat(holding("FUND.MF", "120", fiveDaysAgo).valuationBasis(today)).isEqualTo(Holding.ValuationBasis.MARKET);
        assertThat(holding("X.NS", "120", null).valuationBasis(today)).isEqualTo(Holding.ValuationBasis.STALE);
    }

    @Test
    void applyPriceIgnoresAZeroQuoteRatherThanOverwritingAGoodPrice() {
        Holding h = holding("X.NS", "120", LocalDate.of(2026, 9, 28));
        h.applyPrice(BigDecimal.ZERO, LocalDate.of(2026, 9, 29));
        assertThat(h.getCurrentPrice()).isEqualByComparingTo("120");
        assertThat(h.getPriceAsOf()).isEqualTo(LocalDate.of(2026, 9, 28));
    }
}
