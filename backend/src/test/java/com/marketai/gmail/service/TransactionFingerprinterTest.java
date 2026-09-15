package com.marketai.gmail.service;

import com.marketai.gmail.parser.ParsedEmail;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionFingerprinterTest {

    private final TransactionFingerprinter fp = new TransactionFingerprinter();

    private ParsedEmail trade(String symbol, int qty, String price, LocalDate date) {
        ParsedEmail pe = new ParsedEmail();
        pe.setType(ParsedEmail.Type.TRADE_BUY);
        pe.setSymbol(symbol);
        pe.setQuantity(qty);
        pe.setPrice(new BigDecimal(price));
        pe.setTradeDate(date);
        return pe;
    }

    @Test
    void sameTransactionFingerprintsIdentically_regardlessOfSourceDescription() {
        ParsedEmail a = trade("HDFCBANK", 10, "1500.00", LocalDate.of(2026, 1, 15));
        ParsedEmail b = trade("HDFCBANK", 10, "1500.00", LocalDate.of(2026, 1, 15));
        // A forwarded copy of the same alert carries different surrounding text — which must
        // not change the fingerprint, or the resend would be imported a second time.
        a.setSourceDescription("Bought 10 HDFCBANK @ 1500");
        b.setSourceDescription("FWD: FWD: your trade confirmation");

        assertThat(fp.fingerprint(a)).isEqualTo(fp.fingerprint(b));
    }

    @Test
    void amountFormattingDoesNotChangeFingerprint() {
        ParsedEmail a = trade("TITAN", 5, "1000", LocalDate.of(2026, 2, 1));
        ParsedEmail b = trade("TITAN", 5, "1000.00", LocalDate.of(2026, 2, 1));

        assertThat(fp.fingerprint(a)).isEqualTo(fp.fingerprint(b));
    }

    @Test
    void merchantAndBankMatchingIsCaseAndWhitespaceInsensitive() {
        ParsedEmail a = new ParsedEmail();
        a.setType(ParsedEmail.Type.FD_OPEN);
        a.setBank("HDFC Bank");
        a.setPrincipal(new BigDecimal("100000"));
        a.setStartDate(LocalDate.of(2026, 3, 1));

        ParsedEmail b = new ParsedEmail();
        b.setType(ParsedEmail.Type.FD_OPEN);
        b.setBank("  hdfc   bank ");
        b.setPrincipal(new BigDecimal("100000.00"));
        b.setStartDate(LocalDate.of(2026, 3, 1));

        assertThat(fp.fingerprint(a)).isEqualTo(fp.fingerprint(b));
    }

    @Test
    void genuinelyDifferentTransactionsFingerprintDifferently() {
        LocalDate d = LocalDate.of(2026, 1, 15);
        String base = fp.fingerprint(trade("HDFCBANK", 10, "1500.00", d));

        assertThat(fp.fingerprint(trade("HDFCBANK", 11, "1500.00", d))).isNotEqualTo(base);   // qty
        assertThat(fp.fingerprint(trade("HDFCBANK", 10, "1501.00", d))).isNotEqualTo(base);   // price
        assertThat(fp.fingerprint(trade("TITAN", 10, "1500.00", d))).isNotEqualTo(base);      // symbol
        assertThat(fp.fingerprint(trade("HDFCBANK", 10, "1500.00", d.plusDays(1)))).isNotEqualTo(base); // date
    }

    @Test
    void aSellOfTheSameQuantityIsNotTheSameAsABuy() {
        LocalDate d = LocalDate.of(2026, 1, 15);
        ParsedEmail buy = trade("HDFCBANK", 10, "1500.00", d);
        ParsedEmail sell = trade("HDFCBANK", 10, "1500.00", d);
        sell.setType(ParsedEmail.Type.TRADE_SELL);

        assertThat(fp.fingerprint(buy)).isNotEqualTo(fp.fingerprint(sell));
    }

    @Test
    void producesA64CharHexSha256() {
        assertThat(fp.fingerprint(trade("HDFCBANK", 1, "100", LocalDate.of(2026, 1, 1))))
            .hasSize(64).matches("[0-9a-f]{64}");
    }
}
