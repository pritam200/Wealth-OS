package com.marketai.gmail.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The bank alert templates seen in a real inbox, with the account numbers made up. */
class BankAlertParsersTest {

    private static final String HOLDER = "PRITAM KUMAR SINGH";
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode only(String domain, String body) throws Exception {
        var r = BankAlertParsers.parse(domain, "alert", body, HOLDER);
        assertThat(r).as("template recognised").isPresent();
        JsonNode txns = json.readTree(r.get().json()).get("transactions");
        assertThat(txns).hasSize(1);
        return txns.get(0);
    }

    @Test
    @DisplayName("Axis UPI debit: amount, date, account and payee from the alert's fields")
    void axisUpi() throws Exception {
        JsonNode t = only("axis.bank.in", "Dear Customer,\nAmount Debited:\nINR 2129.00\nAccount Number:\nXX6248\n"
            + "Date & Time:\n02-10-26, 18:39:14 IST\nTransaction Info:\nUPI/P2M/123459734/SUNIL STORE\nIf this was not you…");
        assertThat(t.get("transaction_type").asText()).isEqualTo("DEBIT");
        assertThat(t.get("amount_inr").decimalValue()).isEqualByComparingTo("2129.00");
        assertThat(t.get("transaction_date").asText()).isEqualTo("2026-10-02");
        assertThat(t.get("merchant").asText()).isEqualTo("SUNIL STORE");
        assertThat(t.get("evidence").asText()).contains("INR 2129.00").contains("02-10-26");
    }

    @Test
    @DisplayName("Axis account debit names the payee before the reference")
    void axisAccount() throws Exception {
        JsonNode t = only("axis.bank.in", "We wish to inform you that your A/c no. XX6248 has been debited with INR 500.00 "
            + "on 08-10-2026 00:09:29 IST by HDFC Small Ca/12345072/HA.\nAvailable balance…");
        assertThat(t.get("merchant").asText()).isEqualTo("HDFC Small Ca");
        assertThat(t.get("transaction_date").asText()).isEqualTo("2026-10-08");
    }

    @Test
    @DisplayName("HDFC UPI from the RuPay card and from the account, with the payee's name when given")
    void hdfcUpi() throws Exception {
        JsonNode card = only("hdfcbank.bank.in", "Dear Customer, Rs.141.50 has been debited from your RuPay Credit Card "
            + "(ending 4286) Paid to KPNFARMFRESHOFFLINE@ybl Date: 11-10-26 Your UPI transaction reference number is 1234.");
        assertThat(card.get("merchant").asText()).isEqualTo("KPNFARMFRESHOFFLINE@ybl");
        assertThat(card.get("payment_method").asText()).contains("4286");
        assertThat(card.get("transaction_date").asText()).isEqualTo("2026-10-11");

        JsonNode acct = only("hdfcbank.bank.in", "Rs.15000.00 is debited from your account ending 2548 towards VPA "
            + "hdfcmf.channel.mf@validicici (HDFC MUTUAL FUND) on 29-09-26.");
        assertThat(acct.get("merchant").asText()).isEqualTo("HDFC MUTUAL FUND");
        assertThat(acct.get("amount_inr").decimalValue()).isEqualByComparingTo("15000");
    }

    @Test
    @DisplayName("HDFC mandate debit names the fund house")
    void hdfcMandate() throws Exception {
        JsonNode t = only("hdfcbank.bank.in", "Rs.5000.00 has been debited from HDFC Bank Account Number XXXXXXXXXX2548 "
            + "towards HDFC Mutual Fund OTM/12340633 with UMRN HDFC00012408 on 22-Oct-2026.");
        assertThat(t.get("merchant").asText()).isEqualTo("HDFC Mutual Fund");
        assertThat(t.get("transaction_date").asText()).isEqualTo("2026-10-22");
    }

    @Test
    @DisplayName("SBI: a transfer to the holder's own name is an own-account transfer; a hold is no transaction")
    void sbi() throws Exception {
        JsonNode out = only("alerts.sbi.bank.in", "Your A/C XXXXX953801 Debited INR 88.00 on 20/10/26 -Transferred to "
            + "Mr. PRITAM KUMARSI. Avl Balance INR 1,50,000.00-SBI");
        assertThat(out.get("transaction_type").asText()).isEqualTo("DEBIT");
        assertThat(out.get("sub_type").asText()).isEqualTo("OWN_TRANSFER");
        assertThat(out.get("transaction_date").asText()).isEqualTo("2026-10-20");

        JsonNode in = only("alerts.sbi.bank.in", "Your A/C XXXXX429546 Credited INR 88.00 on 20/10/26 -Deposit by "
            + "transfer from Mr. Pritam Kumar.");
        assertThat(in.get("sub_type").asText()).isEqualTo("OWN_TRANSFER");

        JsonNode other = only("alerts.sbi.bank.in", "Your A/C XXXXX429546 Debited INR 450.00 on 05/10/26 -Transferred to "
            + "Mr. RAHUL SHARMA. Avl Balance INR 1,000.00-SBI");
        assertThat(other.get("sub_type").isNull()).isTrue();

        JsonNode has = only("alerts.sbi.bank.in", "Your A/C XXXXX429546 has a debit by transfer of Rs 4,000.00 on 05/10/26. "
            + "Avl Bal Rs 44,913.40.-SBI");
        assertThat(has.get("amount_inr").decimalValue()).isEqualByComparingTo("4000");

        var hold = BankAlertParsers.parse("alerts.sbi.bank.in", "CBSSBI ALERT",
            "hold for INR 26,856.00 created in deposit Ac XXXXX429546 on 22/10/26.", HOLDER);
        assertThat(hold).isPresent();
        assertThat(json.readTree(hold.get().json()).get("transactions")).isEmpty();
    }

    @Test
    @DisplayName("an unknown template or sender is left to the extractor")
    void unknownFallsBack() {
        assertThat(BankAlertParsers.parse("axis.bank.in", "x", "Your SIP is due on 5th", HOLDER)).isEmpty();
        assertThat(BankAlertParsers.parse("indusind.com", "x", "Rs.100 debited", HOLDER)).isEmpty();
    }

    @Test
    @DisplayName("offers and reminders state no amount; alerts and statement lines do")
    void statesAmount() {
        assertThat(BankAlertParsers.statesAmount("Begin your savings journey with 7% p.a. assured returns")).isFalse();
        assertThat(BankAlertParsers.statesAmount("Get an IndusInd Bank Add-on Credit Card today")).isFalse();
        assertThat(BankAlertParsers.statesAmount("Rs.500 has been debited from your card")).isTrue();
        assertThat(BankAlertParsers.statesAmount("10-01-2026 Swiggy 1,200.00 Dr")).isTrue();
        assertThat(BankAlertParsers.statesAmount("₹60000 converted to EMI")).isTrue();
    }

    @Test
    @DisplayName("the holder's name is recognised however a bank cuts it short")
    void holderName() {
        assertThat(BankAlertParsers.isHolder("PRITAM KUMARSI", HOLDER)).isTrue();
        assertThat(BankAlertParsers.isHolder("Pritam Kumar", HOLDER)).isTrue();
        assertThat(BankAlertParsers.isHolder("PRITAM KUMAR SINGH", HOLDER)).isTrue();
        assertThat(BankAlertParsers.isHolder("Pritam Das", HOLDER)).isFalse();
        assertThat(BankAlertParsers.isHolder("SUNIL STORE", HOLDER)).isFalse();
        assertThat(BankAlertParsers.isHolder("Pritam", HOLDER)).isFalse();
    }
}
