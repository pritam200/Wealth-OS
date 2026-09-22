package com.marketai.gmail.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CardPaymentParserTest {

    private final CardPaymentParser parser = new CardPaymentParser();

    @Test
    @DisplayName("a successful payment confirmation is parsed as CARD_PAYMENT / CONFIRMED")
    void parsesConfirmedPayment() {
        String subject = "Payment Received on your HDFC Credit Card";
        String body = "We have received a payment of Rs. 5,000.00 towards your HDFC Bank Credit Card "
            + "ending 1234 on 04-03-2026. Reference No: RRN123456789.";

        assertThat(parser.canParse("alerts@hdfcbank.net", subject)).isTrue();
        List<ParsedEmail> out = parser.parse("alerts@hdfcbank.net", subject, body);

        assertThat(out).hasSize(1);
        ParsedEmail pe = out.get(0);
        assertThat(pe.getType()).isEqualTo(ParsedEmail.Type.CARD_PAYMENT);
        assertThat(pe.getAmount()).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(pe.getCardLast4()).isEqualTo("1234");
        assertThat(pe.getPaymentDate()).isEqualTo(LocalDate.of(2026, 3, 4));
        assertThat(pe.getPaymentReference()).isEqualTo("RRN123456789");
        assertThat(pe.getPaymentStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("a failed/reversed payment notice is tagged REVERSED, not CONFIRMED")
    void parsesReversedPayment() {
        String subject = "Payment Failed for your ICICI Credit Card";
        String body = "Your payment of Rs. 2,000.00 towards card ending 5678 was declined and reversed.";

        List<ParsedEmail> out = parser.parse("noreply@icicibank.com", subject, body);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getPaymentStatus()).isEqualTo("REVERSED");
    }

    @Test
    @DisplayName("an email with no parseable amount produces nothing")
    void noAmountProducesNoResult() {
        List<ParsedEmail> out = parser.parse("alerts@hdfcbank.net",
            "Payment Received on your HDFC Credit Card", "Thanks for banking with us.");
        assertThat(out).isEmpty();
    }
}
