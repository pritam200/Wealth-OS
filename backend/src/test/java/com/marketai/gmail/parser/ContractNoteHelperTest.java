package com.marketai.gmail.parser;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContractNoteHelperTest {

    @Test
    void parsesFourDecimalMstockPrices() {
        // Regression test for the CN_DECIMAL bug: mStock contract notes print 4 decimal
        // places (410.0000) while the old regex only matched exactly 2 (\.\d{2}), so
        // decs=[] and every mStock trade line was silently dropped as "CN BAD QTY/PRICE".
        String text = "Contract Note 05-Aug-2024\n" +
            "BHEL BUY 10 410.0000 0.0000 410.0000 0.0000 -4100.0000\n";
        List<ParsedEmail> trades = ContractNoteHelper.parseContractNoteRows(text, "Contract Note", "MStock");
        assertThat(trades).hasSize(1);
        assertThat(trades.get(0).getSymbol()).isEqualTo("BHEL");
        assertThat(trades.get(0).getQuantity()).isEqualTo(10);
        assertThat(trades.get(0).getPrice()).isEqualByComparingTo(new BigDecimal("410.00"));
    }

    @Test
    void neverPicksBrokerClientCodeAsSymbol() {
        // Regression test for the Aug-2026 incident: "MA7468533" (a client code printed at
        // the top of the contract note) was picked up by findSymbol() as if it were the
        // traded stock, before the real symbol later in the same line.
        String text = "Client Code: MA7468533\n" +
            "ICICIBANK EQ BUY 4 1442.80 0.00 1442.80 0.00 -5771.20\n";
        List<ParsedEmail> trades = ContractNoteHelper.parseContractNoteRows(text, "Contract Note", "Broker");
        assertThat(trades).isNotEmpty();
        assertThat(trades).noneMatch(t -> "MA7468533".equals(t.getSymbol()));
    }

    @Test
    void skipsHeaderAndTotalLines() {
        String text = "Sr. No. Symbol Qty Rate Amount\n" +
            "Grand Total 100 5000.00\n" +
            "RELIANCE BUY 5 1285.14 0.00 1285.14 0.00 -6425.70\n";
        List<ParsedEmail> trades = ContractNoteHelper.parseContractNoteRows(text, "Contract Note", "Broker");
        assertThat(trades).extracting(ParsedEmail::getSymbol).containsExactly("RELIANCE");
    }

    @Test
    void emptyOrNullTextReturnsNoTrades() {
        assertThat(ContractNoteHelper.parseContractNoteRows(null, "s", "b")).isEmpty();
        assertThat(ContractNoteHelper.parseContractNoteRows("", "s", "b")).isEmpty();
    }
}
