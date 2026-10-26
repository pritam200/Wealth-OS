package com.marketai.gmail.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Fund and dividend confirmations laid out as registrars send them, with every value made up. */
class LearnedFormsTest {

    private final ObjectMapper json = new ObjectMapper();

    static String sip(String scheme, String folio, String date, String amount, String nav, String units) {
        return "Dear Investor, PAN: ABCDE1234F. We have processed your systematic purchase. "
            + "Folio No | " + folio + " | Scheme | " + scheme + " | Transaction Date | " + date
            + " | NAV (Rs.) | " + nav + " | Units allotted | " + units + " | Instalment | 3/120 "
            + "| Net Amount (Rs.) | " + amount + " | Registered address: 4 Lake Road, Chennai 600004. "
            + "For any queries write to service@example-registrar.com. This is a system generated mail.";
    }

    private JsonNode t(String s) throws Exception {
        return json.readTree(s);
    }

    @Test
    @DisplayName("a confirmation learned once reads the next one: another scheme, folio, date and figures")
    void learnsAFundForm() throws Exception {
        String first = sip("ICICI Prudential Bluechip Fund - Direct Plan - Growth", "12345678/90", "05-Oct-2026", "4,999.75", "112.3456", "44.503");
        var shape = LearnedTemplates.learn(first, t("""
            {"instrument_type":"MF","transaction_type":"SIP","scheme_name":"ICICI Prudential Bluechip Fund - Direct Plan - Growth",
             "folio_number":"12345678/90","transaction_date":"2026-10-05","amount_inr":4999.75,"nav":112.3456,"units":44.503,
             "evidence":"Net Amount (Rs.) | 4,999.75"}
            """), 1);
        assertThat(shape).isPresent();
        assertThat(shape.get().pattern()).doesNotContain("ABCDE").doesNotContain("service@");

        JsonNode next = LearnedTemplates.read(shape.get(),
            sip("ICICI Prudential Nifty 50 Index Fund - Direct Plan - Growth", "87654321/12", "5-Nov-2026", "999.95", "25.10", "39.839")).orElseThrow();
        assertThat(next.get("scheme_name").asText()).isEqualTo("ICICI Prudential Nifty 50 Index Fund - Direct Plan - Growth");
        assertThat(next.get("folio_number").asText()).isEqualTo("87654321/12");
        assertThat(next.get("transaction_date").asText()).isEqualTo("2026-11-05");
        assertThat(next.get("amount_inr").decimalValue()).isEqualByComparingTo("999.95");
        assertThat(next.get("nav").decimalValue()).isEqualByComparingTo("25.10");
        assertThat(next.get("units").decimalValue()).isEqualByComparingTo("39.839");
        assertThat(next.get("transaction_type").asText()).isEqualTo("SIP");
        assertThat(LearnedTemplates.norm(next.get("evidence").asText())).contains("999.95");
    }

    @Test
    @DisplayName("not learned from an email with two transactions, or when a figure the model read isn't in it")
    void refusesUnsafeForms() throws Exception {
        String email = sip("ICICI Prudential Bluechip Fund - Direct Plan - Growth", "12345678/90", "05-Oct-2026", "4,999.75", "112.3456", "44.503");
        String read = """
            {"instrument_type":"MF","transaction_type":"SIP","scheme_name":"ICICI Prudential Bluechip Fund - Direct Plan - Growth",
             "transaction_date":"2026-10-05","amount_inr":4999.75,"units":%s}
            """;
        assertThat(LearnedTemplates.learn(email, t(read.formatted("44.503")), 2)).as("one of two").isEmpty();
        assertThat(LearnedTemplates.learn(email, t(read.formatted("45.000")), 1)).as("units not in the email").isEmpty();
        assertThat(LearnedTemplates.learn(email, t("""
            {"instrument_type":"MF","transaction_type":"SIP","transaction_date":"2026-10-05","amount_inr":4999.75}
            """), 1)).as("a fund read without its scheme").isEmpty();
    }

    @Test
    @DisplayName("a form doesn't read an email laid out differently")
    void otherLayoutNotRead() throws Exception {
        String email = sip("ICICI Prudential Bluechip Fund - Direct Plan - Growth", "12345678/90", "05-Oct-2026", "4,999.75", "112.3456", "44.503");
        var shape = LearnedTemplates.learn(email, t("""
            {"instrument_type":"MF","transaction_type":"SIP","scheme_name":"ICICI Prudential Bluechip Fund - Direct Plan - Growth",
             "transaction_date":"2026-10-05","amount_inr":4999.75,"units":44.503}
            """), 1).orElseThrow();
        assertThat(LearnedTemplates.read(shape, email.replace("Units allotted", "Units redeemed"))).isEmpty();
        assertThat(LearnedTemplates.read(shape, email + " " + email)).isEmpty();
    }
}
