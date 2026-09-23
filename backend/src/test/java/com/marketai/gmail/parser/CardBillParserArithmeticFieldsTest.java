package com.marketai.gmail.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parser now extracts the operands {@code ArithmeticValidator.statementBalances} needs
 * (previous balance, this cycle's debits/credits) alongside the figures it already captured, so
 * a card statement's own internal redundancy can be checked instead of trusting the total-due
 * regex on its own.
 */
class CardBillParserArithmeticFieldsTest {

    private final CardBillParser parser = new CardBillParser();

    @Test
    @DisplayName("previous balance, minimum due, and cycle debits/credits are all extracted")
    void extractsArithmeticOperands() {
        String subject = "Your HDFC Credit Card Statement";
        String body = "Previous Balance Rs 12,000.00. Purchases & Other Debits Rs 33,230.00. "
            + "Payments & Other Credits Rs 0.00. Total Amount Due Rs 45,230.00. "
            + "Minimum Amount Due Rs 2,262.00. Due Date: 25-02-2026. Card Number ending 1234.";

        List<ParsedEmail> out = parser.parse("alerts@hdfcbank.net", subject, body);

        assertThat(out).hasSize(1);
        ParsedEmail pe = out.get(0);
        assertThat(pe.getPreviousBalance()).isEqualByComparingTo(new BigDecimal("12000.00"));
        assertThat(pe.getCycleDebits()).isEqualByComparingTo(new BigDecimal("33230.00"));
        assertThat(pe.getCycleCredits()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(pe.getMinimumDue()).isEqualByComparingTo(new BigDecimal("2262.00"));
        assertThat(pe.getAmount()).isEqualByComparingTo(new BigDecimal("45230.00"));
    }

    @Test
    @DisplayName("a template that omits the breakdown still parses — the new fields are simply null")
    void missingOperandsAreNullNotFatal() {
        String subject = "Your HDFC Credit Card Bill";
        String body = "Total Amount Due: Rs. 3,000.00. Due Date: 15-04-2026.";

        List<ParsedEmail> out = parser.parse("alerts@hdfcbank.net", subject, body);

        assertThat(out).hasSize(1);
        ParsedEmail pe = out.get(0);
        assertThat(pe.getAmount()).isEqualByComparingTo(new BigDecimal("3000.00"));
        assertThat(pe.getPreviousBalance()).isNull();
        assertThat(pe.getCycleDebits()).isNull();
        assertThat(pe.getCycleCredits()).isNull();
        assertThat(pe.getMinimumDue()).isNull();
    }
}
