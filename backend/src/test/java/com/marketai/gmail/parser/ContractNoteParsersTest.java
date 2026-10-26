package com.marketai.gmail.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** An m.Stock contract note laid out as its PDF text reads, with the numbers made up. */
class ContractNoteParsersTest {

    static final String SUBJECT = "Contract Note as on Oct 13 2026 Ref No.:(3986423) of MA1234567";
    static final String NOTE = String.join("\n",
        "CONTRACT NOTE CUM TAX INVOICE",
        "Trade Date : 13/10/2026",
        "Order No. Order Time Trade No. Trade Time Security / Contract Description Buy(B) / Sell(S) Quantity Gross Rate",
        "1000000000080 09:57:24 1488255 09:57:24 NSE - M BHARTI AIRTEL LIMITE SELL 40 1888.0000 0.0000 1888.0000 0.0000 75520.0000",
        "1000000000080 09:57:24 1488255 09:57:24 NSE - M BHARTI AIRTEL LIMITE SELL 55 1888.0000 0.0000 1888.0000 0.0000 103840.0000",
        "1000000001382 12:59:24 1200001842 12:59:24 NSE - M LARSEN & TOUBRO LTD. BUY 5 3648.7000 0.0000 3648.7000 0.0000 -18243.5000",
        "1000000000057 11:20:13 3527598 11:20:39 NSEFO - BT_O OPTIDX NIFTY 13OCT26 22150.00 PE (BT) BUY 65 24.6000 0.0000 24.6000 0.0000 -1599.0000",
        "1000000000178 00:00:00 NSEFO - BT_O OPTIDX NIFTY 13OCT26 22150.00 PE (BT) 0 0.0000 5.0000 0.0000 0.0000 -5.0000",
        "ISIN Security Name Buy Qty WAP Sell Qty WAP Net",
        "INE397D01024 BHARTI AIRTEL LIMITE 95 1888.0000 0.0526 1887.9474 179355.0000 0 179355.0000",
        "INE018A01030 LARSEN & TOUBRO LTD 5 3648.7000 3648.7000 18243.5000 5 -18243.5000");

    @Test
    @DisplayName("every fill is read with its side, size, price, trade number, ISIN and the note's trade date")
    void readsFills() throws Exception {
        var r = ContractNoteParsers.parse("mstock.com", SUBJECT, NOTE);
        assertThat(r).isPresent();
        JsonNode txns = new ObjectMapper().readTree(r.get().json()).get("transactions");
        assertThat(txns).hasSize(4);

        JsonNode first = txns.get(0);
        assertThat(first.get("instrument_type").asText()).isEqualTo("EQUITY");
        assertThat(first.get("transaction_type").asText()).isEqualTo("SELL");
        assertThat(first.get("quantity").asInt()).isEqualTo(40);
        assertThat(first.get("price").decimalValue()).isEqualByComparingTo("1888");
        assertThat(first.get("amount_inr").decimalValue()).isEqualByComparingTo("75520");
        assertThat(first.get("trade_number").asText()).isEqualTo("1488255");
        assertThat(first.get("isin").asText()).isEqualTo("INE397D01024");
        assertThat(first.get("transaction_date").asText()).isEqualTo("2026-10-13");
        assertThat(NOTE).contains(first.get("evidence").asText());

        assertThat(txns.get(1).get("quantity").asInt()).as("second fill, same trade number").isEqualTo(55);

        JsonNode lt = txns.get(2);
        assertThat(lt.get("transaction_type").asText()).isEqualTo("BUY");
        assertThat(lt.get("isin").asText()).as("'LTD.' in the trade row matches 'LTD' in the summary").isEqualTo("INE018A01030");
        assertThat(lt.get("amount_inr").decimalValue()).isEqualByComparingTo("18243.5");

        JsonNode option = txns.get(3);
        assertThat(option.get("instrument_type").asText()).isEqualTo("FNO");
    }

    @Test
    @DisplayName("the trade date comes from the subject when the note doesn't print one")
    void dateFromSubject() {
        assertThat(ContractNoteParsers.tradeDate(SUBJECT, "no date here")).isEqualTo(LocalDate.of(2026, 10, 13));
        assertThat(ContractNoteParsers.tradeDate("Weekly statement", "no date here")).isNull();
    }

    @Test
    @DisplayName("other m.Stock mail and other brokers are left to the extractor")
    void onlyMstockContractNotes() {
        assertThat(ContractNoteParsers.parse("mstock.com", "Weekly Register of Funds (ROF)", NOTE)).isEmpty();
        assertThat(ContractNoteParsers.parse("zerodha.com", SUBJECT, NOTE)).isEmpty();
    }
}
