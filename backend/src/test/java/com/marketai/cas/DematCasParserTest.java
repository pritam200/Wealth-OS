package com.marketai.cas;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class DematCasParserTest {

    static final String CAS = String.join("\n",
        "NSDL Consolidated Account Statement",
        "Statement for the period from 01-Mar-2024 to 31-Mar-2024",
        "Holdings as on 31-Mar-2024",
        "NSDL Demat Account  DP ID:IN300476 Client ID:12345678",
        "ISIN  Security  Current Bal  Frozen  Market Price  Value",
        "INE002A01018 RELIANCE INDUSTRIES LTD 100.000 2,950.00 2,95,000.00",
        "INE040A01034 HDFC BANK LTD 50.000 1,500.00 75,000.00",
        "INF179K01YZ4 HDFC TOP 100 FUND - DIRECT GROWTH 120.456 99.50 11,985.00",
        "INE009A01021 INFOSYS LTD 10.000 1,500.00 20,000.00",
        "INE999Z01011 BROKEN LINE WITH NO NUMBERS");

    @Test
    void readsAccountDateAndHoldings() {
        DematCasParser.Result r = DematCasParser.parse(CAS);
        assertThat(DematCasParser.looksLikeDemat(CAS)).isTrue();
        assertThat(r.asOf).isEqualTo(LocalDate.of(2024, 3, 31));
        assertThat(r.holdings).hasSize(4);
        DematCasParser.Holding rel = r.holdings.get(0);
        assertThat(rel.account).isEqualTo("DP IN300476 / Client 12345678");
        assertThat(rel.isin).isEqualTo("INE002A01018");
        assertThat(rel.name).isEqualTo("RELIANCE INDUSTRIES LTD");
        assertThat(rel.quantity).isEqualByComparingTo("100");
        assertThat(rel.price).isEqualByComparingTo("2950");
        assertThat(rel.value).isEqualByComparingTo("295000");
        assertThat(rel.valueChecks).isTrue();
    }

    @Test
    void flagsInconsistentValuesAndUnreadableLines() {
        DematCasParser.Result r = DematCasParser.parse(CAS);
        assertThat(r.holdings.get(3).valueChecks).isFalse(); // 10 x 1,500 != 20,000
        assertThat(r.unparsed).hasSize(1);
    }

    @Test
    void aCamsMutualFundCasIsNotMistakenForDemat() {
        assertThat(DematCasParser.looksLikeDemat(CasParserTest.CAS)).isFalse();
    }
}
