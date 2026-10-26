package com.marketai.gmail.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The CAMS templates seen in a real inbox, with the folio made up. */
public class FundEmailParsersTest {

    public static final String PURCHASE = "Dear Investor,\nWe confirm having processed your purchase in\n"
        + "SBI Aggressive Hybrid Fund - Direct Growth for value date 25-Sep-2026 for Rs.\n"
        + "499.98 at NAV of 344.1641 for units 1.453 in Folio No.\n12345678.\nRegards";
    public static final String SIP_DUE = "We would like to inform you that your SIP instalment of Rs. 500 in SBI Aggressive "
        + "Hybrid Fund Dir Growth under Folio 12345678, Frequency Daily(ZIP) is due on 07-OCT-2026.";

    @Test
    @DisplayName("a purchase confirmation is read with its scheme, value date, amount, NAV, units and folio")
    void purchase() throws Exception {
        var r = FundEmailParsers.parse("camsonline.com", "SBI Mutual Fund - Purchase transaction confirmation", PURCHASE);
        assertThat(r).isPresent();
        JsonNode txns = new ObjectMapper().readTree(r.get().json()).get("transactions");
        assertThat(txns).hasSize(1);
        JsonNode t = txns.get(0);
        assertThat(t.get("instrument_type").asText()).isEqualTo("MF");
        assertThat(t.get("transaction_type").asText()).isEqualTo("PURCHASE");
        assertThat(t.get("scheme_name").asText()).isEqualTo("SBI Aggressive Hybrid Fund - Direct Growth");
        assertThat(t.get("transaction_date").asText()).isEqualTo("2026-09-25");
        assertThat(t.get("amount_inr").decimalValue()).isEqualByComparingTo("499.98");
        assertThat(t.get("nav").decimalValue()).isEqualByComparingTo("344.1641");
        assertThat(t.get("units").decimalValue()).isEqualByComparingTo("1.453");
        assertThat(t.get("folio_number").asText()).isEqualTo("12345678");
        assertThat(PURCHASE.replaceAll("\\s+", " ")).contains(t.get("evidence").asText());
    }

    @Test
    @DisplayName("an SIP instalment that is only due books nothing")
    void sipDue() throws Exception {
        var r = FundEmailParsers.parse("camsonline.com", "SBI Mutual Fund- SIP-installment intimation", SIP_DUE);
        assertThat(r).isPresent();
        assertThat(new ObjectMapper().readTree(r.get().json()).get("transactions")).isEmpty();
    }

    @Test
    @DisplayName("other senders and other CAMS mail are left to the extractor")
    void otherMail() {
        assertThat(FundEmailParsers.parse("kfintech.com", "x", PURCHASE)).isEmpty();
        assertThat(FundEmailParsers.parse("camsonline.com", "Base Expense Ratio", "The expense ratio changes from 1 Nov")).isEmpty();
    }
}
