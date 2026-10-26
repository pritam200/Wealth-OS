package com.marketai.gmail.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Alert wordings seen in a real inbox, with the numbers made up. */
public class LearnedTemplatesTest {

    private final ObjectMapper json = new ObjectMapper();

    private JsonNode t(String s) throws Exception {
        return json.readTree(s);
    }

    public static final String KOTAK_1 = "Dear Customer, Rs.1,250.00 is debited from your Kotak Bank account XX4521 on 03-10-2026 "
        + "towards UPI payment to SWIGGY LIMITED. UPI Ref 427712345678. Not you? Call 1860 266 2666.";
    public static final String KOTAK_2 = "Dear Customer, Rs.86.00 is debited from your Kotak Bank account XX4521 on 19-10-2026 "
        + "towards UPI payment to BLINKIT COMMERCE. UPI Ref 429934567890. Not you? Call 1860 266 2666.";

    @Test
    @DisplayName("a learned alert reads the next alert in that wording: its amount, date, payee and reference")
    void learnsAndReadsTheNextAlert() throws Exception {
        var shape = LearnedTemplates.learn(KOTAK_1, t("""
            {"instrument_type":"UPI","transaction_type":"DEBIT","amount_inr":1250,"transaction_date":"2026-10-03",
             "merchant":"SWIGGY LIMITED","trade_number":"427712345678","status":"SUCCESS",
             "evidence":"Rs.1,250.00 is debited from your Kotak Bank account XX4521 on 03-10-2026 towards UPI payment to SWIGGY LIMITED. UPI Ref 427712345678."}
            """));
        assertThat(shape).isPresent();

        JsonNode next = LearnedTemplates.read(shape.get(), KOTAK_2).orElseThrow();
        assertThat(next.get("transaction_type").asText()).isEqualTo("DEBIT");
        assertThat(next.get("amount_inr").decimalValue()).isEqualByComparingTo("86");
        assertThat(next.get("transaction_date").asText()).isEqualTo("2026-10-19");
        assertThat(next.get("merchant").asText()).isEqualTo("BLINKIT COMMERCE");
        assertThat(next.get("trade_number").asText()).isEqualTo("429934567890");
        assertThat(LearnedTemplates.norm(KOTAK_2)).contains(next.get("evidence").asText());
    }

    @Test
    @DisplayName("other wording, or the wording twice in one email, is not read")
    void onlyTheSameWordingOnce() throws Exception {
        var shape = LearnedTemplates.learn(KOTAK_1, t("""
            {"instrument_type":"UPI","transaction_type":"DEBIT","amount_inr":1250,"transaction_date":"2026-10-03",
             "merchant":"SWIGGY LIMITED","evidence":"Rs.1,250.00 is debited from your Kotak Bank account XX4521 on 03-10-2026 towards UPI payment to SWIGGY LIMITED."}
            """)).orElseThrow();
        assertThat(LearnedTemplates.read(shape, "Rs.86.00 is credited to your Kotak Bank account XX4521 on 19-10-2026 from BLINKIT. Thanks")).isEmpty();
        assertThat(LearnedTemplates.read(shape, KOTAK_2 + " " + KOTAK_2)).isEmpty();
    }

    @Test
    @DisplayName("statements, quotes the email doesn't contain, and too-short wording are not learned")
    void refusesWhatItCannotLearnSafely() throws Exception {
        assertThat(LearnedTemplates.learn(KOTAK_1, t("""
            {"instrument_type":"MF","transaction_type":"PURCHASE","amount_inr":1250,"transaction_date":"2026-10-03","evidence":"Rs.1,250.00 is debited"}
            """))).as("not a bank alert").isEmpty();
        assertThat(LearnedTemplates.learn(KOTAK_1, t("""
            {"instrument_type":"UPI","transaction_type":"DEBIT","amount_inr":1250,"transaction_date":"2026-10-03","evidence":"Rs 1250 paid on 3 Oct"}
            """))).as("quote not in the email").isEmpty();
        assertThat(LearnedTemplates.learn("1,250.00 03-10-2026", t("""
            {"instrument_type":"UPI","transaction_type":"DEBIT","amount_inr":1250,"transaction_date":"2026-10-03","evidence":"1,250.00 03-10-2026"}
            """))).as("no wording of its own").isEmpty();
    }

    @Test
    @DisplayName("an own-account transfer is not learned as one: the next payee may be someone else")
    void ownTransferIsWorkedOutAgain() throws Exception {
        String body = "Your A/C XXXXX953801 Debited INR 88.00 on 20/10/26 -Transferred to Mr. PRITAM KUMARSI. Avl Balance INR 1,50,000.00-SBI";
        var shape = LearnedTemplates.learn(body, t("""
            {"instrument_type":"BANK","transaction_type":"DEBIT","sub_type":"OWN_TRANSFER","amount_inr":88,"transaction_date":"2026-10-20",
             "merchant":"Mr. PRITAM KUMARSI","evidence":"Your A/C XXXXX953801 Debited INR 88.00 on 20/10/26 -Transferred to Mr. PRITAM KUMARSI."}
            """)).orElseThrow();
        JsonNode next = LearnedTemplates.read(shape,
            "Your A/C XXXXX953801 Debited INR 450.00 on 05/10/26 -Transferred to Mr. RAHUL SHARMA. Avl Balance INR 1,000.00-SBI").orElseThrow();
        assertThat(next.has("sub_type")).isFalse();
        assertThat(next.get("merchant").asText()).isEqualTo("Mr. RAHUL SHARMA");
        assertThat(next.get("amount_inr").decimalValue()).isEqualByComparingTo("450");
    }
}
