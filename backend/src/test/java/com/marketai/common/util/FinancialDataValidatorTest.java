package com.marketai.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FinancialDataValidatorTest {

    @Test
    void rejectsExactHeaderWord() {
        assertThat(FinancialDataValidator.looksLikeUnverifiableFundName("Name")).isTrue();
        assertThat(FinancialDataValidator.looksLikeUnverifiableFundName("Folio")).isTrue();
        assertThat(FinancialDataValidator.looksLikeUnverifiableFundName("Cost of Investment")).isTrue();
    }

    @Test
    void rejectsConcatenatedHeaderWords_theActualIncidentCase() {
        // The exact corrupted fund name from the Aug-2026 incident: a CAMS "Statement of
        // Account" table header row ("Name" + "Cost of Investment" columns) got parsed as
        // if it were a single fund name.
        assertThat(FinancialDataValidator.looksLikeUnverifiableFundName("Name Cost of Investment")).isTrue();
    }

    @Test
    void acceptsRealFundNames() {
        assertThat(FinancialDataValidator.looksLikeUnverifiableFundName("HDFC Mid Cap Fund - Direct Growth")).isFalse();
        assertThat(FinancialDataValidator.looksLikeUnverifiableFundName("ICICI Pru Large Cap Fund - Direct Growth")).isFalse();
        assertThat(FinancialDataValidator.looksLikeUnverifiableFundName("Large Cap Fund (erstwhile Bluechip Fund)")).isFalse();
        assertThat(FinancialDataValidator.looksLikeUnverifiableFundName("Axis Bluechip Fund")).isFalse();
    }

    @Test
    void nullAndBlankHandling() {
        assertThat(FinancialDataValidator.looksLikeUnverifiableFundName(null)).isFalse();
        assertThat(FinancialDataValidator.looksLikeUnverifiableFundName("   ")).isTrue();
    }

    @Test
    void detectsClientCodePattern_theActualIncidentCase() {
        // The exact corrupted symbol from the Aug-2026 incident: a broker client code
        // picked up as if it were the traded stock's ticker.
        assertThat(FinancialDataValidator.looksLikeClientCode("MA7468533")).isTrue();
        assertThat(FinancialDataValidator.looksLikeClientCode("AB123456")).isTrue();
    }

    @Test
    void acceptsRealStockSymbols() {
        assertThat(FinancialDataValidator.looksLikeClientCode("RELIANCE")).isFalse();
        assertThat(FinancialDataValidator.looksLikeClientCode("ICICIBANK")).isFalse();
        assertThat(FinancialDataValidator.looksLikeClientCode("BHEL")).isFalse();
        // Short letter+digit tickers below the 5-digit threshold should not be flagged.
        assertThat(FinancialDataValidator.looksLikeClientCode("M100")).isFalse();
    }

    @Test
    void clientCodeNullHandling() {
        assertThat(FinancialDataValidator.looksLikeClientCode(null)).isFalse();
    }
}
