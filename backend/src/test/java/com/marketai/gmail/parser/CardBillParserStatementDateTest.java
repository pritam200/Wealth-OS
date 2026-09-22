package com.marketai.gmail.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CardBillParserStatementDateTest {

    private final CardBillParser parser = new CardBillParser();

    @Test
    @DisplayName("statement date is extracted alongside the existing due date/amount fields")
    void extractsStatementDate() {
        String subject = "Your HDFC Credit Card Statement";
        String body = "Statement Date: 05-02-2026. Total Amount Due: Rs. 12,500.00. "
            + "Due Date: 25-02-2026. Card Number ending 1234.";

        List<ParsedEmail> out = parser.parse("alerts@hdfcbank.net", subject, body);

        assertThat(out).hasSize(1);
        ParsedEmail pe = out.get(0);
        assertThat(pe.getType()).isEqualTo(ParsedEmail.Type.CARD_BILL);
        assertThat(pe.getAmount()).isEqualByComparingTo(new BigDecimal("12500.00"));
        assertThat(pe.getDueDate()).isEqualTo(LocalDate.of(2026, 2, 25));
        assertThat(pe.getStatementDate()).isEqualTo(LocalDate.of(2026, 2, 5));
        assertThat(pe.getCardLast4()).isEqualTo("1234");
    }

    @Test
    @DisplayName("a bill without a recognizable statement date still parses (statementDate null, not fatal)")
    void missingStatementDateIsNullNotFatal() {
        String subject = "Your HDFC Credit Card Bill";
        String body = "Total Amount Due: Rs. 3,000.00. Due Date: 15-04-2026.";

        List<ParsedEmail> out = parser.parse("alerts@hdfcbank.net", subject, body);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getStatementDate()).isNull();
        assertThat(out.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("3000.00"));
    }
}
