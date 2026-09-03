package com.marketai.gmail.parser;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CamsParserTest {

    private final CamsParser parser = new CamsParser();

    @Test
    void canParse_purchaseConfirmationFromCams() {
        assertThat(parser.canParse("SBI Mutual Fund <enq_sbimf@camsonline.com>",
            "Purchase Confirmation - SBI Mutual Fund")).isTrue();
    }

    @Test
    void parsesSchemeNameLabel_theActualIncidentCase() {
        // Regression test for the exact purchase-confirmation email reported unparsed:
        // "Scheme Name:" (not bare "Scheme:") broke the old FUND regex entirely, since the
        // extra word "Name" before the colon meant no valid capture/terminator split existed.
        String body =
            "Dear Investor,\n" +
            "Your transaction has been processed as per details below:\n" +
            "Scheme Name: SBI Equity Hybrid Fund Direct Growth\n" +
            "Folio No: 23655953\n" +
            "Amount: Rs. 499.98\n" +
            "NAV: Rs. 355.6801\n" +
            "Units Allotted: 1.406\n" +
            "Transaction Date: 07-Aug-2026\n";

        List<ParsedEmail> results = parser.parse(
            "SBI Mutual Fund <enq_sbimf@camsonline.com>", "Purchase Confirmation", body);

        assertThat(results).hasSize(1);
        ParsedEmail pe = results.get(0);
        assertThat(pe.getType()).isEqualTo(ParsedEmail.Type.MF_SIP);
        assertThat(pe.getFundName()).isEqualTo("SBI Equity Hybrid Fund Direct Growth");
        assertThat(pe.getFolio()).isEqualTo("23655953");
        assertThat(pe.getProvider()).isEqualTo("SBI");
        assertThat(pe.getAmount()).isEqualByComparingTo(new BigDecimal("499.98"));
        assertThat(pe.getNav()).isEqualByComparingTo(new BigDecimal("355.6801"));
        assertThat(pe.getUnits()).isEqualByComparingTo(new BigDecimal("1.406"));
        assertThat(pe.getTradeDate()).isEqualTo(LocalDate.of(2026, 8, 7));
    }

    @Test
    void parsesBareSchemeLabel_backwardCompatible() {
        String body =
            "Scheme: HDFC Mid Cap Fund - Direct Growth\n" +
            "Folio No: 23326883\n" +
            "Amount: Rs. 5000.00\n" +
            "NAV: Rs. 211.05\n" +
            "Units Allotted: 23.6912\n" +
            "Date: 09-Jun-2025\n";

        List<ParsedEmail> results = parser.parse(
            "no-reply@camsonline.com", "SIP Confirmation", body);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getFundName()).isEqualTo("HDFC Mid Cap Fund - Direct Growth");
        assertThat(results.get(0).getFolio()).isEqualTo("23326883");
    }

    @Test
    void redemptionKeywordSetsCorrectType() {
        String body =
            "Scheme Name: SBI Bluechip Fund Direct Growth\n" +
            "Folio No: 11112222\n" +
            "Amount: Rs. 10000.00\n" +
            "NAV: Rs. 80.50\n" +
            "Units: 124.22\n" +
            "Date: 01-Jan-2026\n";

        List<ParsedEmail> results = parser.parse(
            "enq_sbimf@camsonline.com", "Redemption Confirmation", body);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getType()).isEqualTo(ParsedEmail.Type.MF_REDEEM);
    }

    @Test
    void rejectsUnrelatedEmail() {
        assertThat(parser.canParse("someone@gmail.com", "Hello")).isFalse();
    }

    @Test
    void missingFundNameOrAmountReturnsNoResults() {
        assertThat(parser.parse("enq_sbimf@camsonline.com", "Purchase Confirmation", "no useful fields here")).isEmpty();
    }
}
