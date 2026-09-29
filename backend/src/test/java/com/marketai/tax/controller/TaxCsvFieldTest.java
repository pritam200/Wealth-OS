package com.marketai.tax.controller;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class TaxCsvFieldTest {

    @Test
    void formulaLikeTextIsNeutralised() {
        assertThat(TaxController.csvField("=HYPERLINK(\"http://x\")")).startsWith("\"'=");
        assertThat(TaxController.csvField("+SUM(A1)")).isEqualTo("'+SUM(A1)");
        assertThat(TaxController.csvField("@cmd")).isEqualTo("'@cmd");
    }

    @Test
    void numbersAndDatesStayNumeric() {
        assertThat(TaxController.csvField(new BigDecimal("-1250.50"))).isEqualTo("-1250.50");
        assertThat(TaxController.csvField(LocalDate.of(2026, 4, 1))).isEqualTo("2026-04-01");
    }

    @Test
    void lineBreaksAndCommasAreQuoted() {
        assertThat(TaxController.csvField("HDFC Fund\nGrowth")).isEqualTo("\"HDFC Fund\nGrowth\"");
        assertThat(TaxController.csvField("A, B")).isEqualTo("\"A, B\"");
    }
}
