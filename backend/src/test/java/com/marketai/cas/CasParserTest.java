package com.marketai.cas;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class CasParserTest {

    static final String CAS = String.join("\n",
        "Consolidated Account Statement",
        "01-Jan-2021 To 31-Dec-2022",
        "HDFC Mutual Fund",
        "Folio No: 12345678 / 90   PAN: ABCDE1234F  KYC: OK",
        "HDFCTOP-HDFC Top 100 Fund - Direct Plan - Growth (Advisor: DIRECT)  ISIN: INF179K01YZ4(Demat)  Registrar : CAMS",
        "Opening Unit Balance: 10.000",
        "15-Jun-2021  SIP Purchase - Instalment No 1   5,000.00  50.123  99.7500  60.123",
        "15-Jun-2021  *** Stamp Duty ***  0.25",
        "20-Jul-2022  Redemption   (2,000.00)  (20.000)  100.00  40.123",
        "Closing Unit Balance: 40.123   NAV on 31-Dec-2022: INR 120.50   Total Cost Value: 3,000.00",
        "Axis Mutual Fund",
        "Folio No: 998877",
        "Axis Bluechip Fund - Direct Growth  ISIN: INF846K01DP8(Demat)  Registrar : KFINTECH",
        "Opening Unit Balance: 0.000",
        "03-Mar-2022  Purchase   10,000.00  200.000  50.0000  200.000",
        "10-Oct-2022  Dividend Paid   150.00",
        "Closing Unit Balance: 200.500   NAV on 31-Dec-2022: INR 55.00");

    @Test
    void readsPeriodSchemesAndTransactions() {
        CasParser.Result r = CasParser.parse(CAS);
        assertThat(r.periodFrom).isEqualTo(LocalDate.of(2021, 1, 1));
        assertThat(r.periodTo).isEqualTo(LocalDate.of(2022, 12, 31));
        assertThat(r.schemes).hasSize(2);

        CasParser.Scheme hdfc = r.schemes.get(0);
        assertThat(hdfc.amc).isEqualTo("HDFC Mutual Fund");
        assertThat(hdfc.folio).isEqualTo("12345678/90");
        assertThat(hdfc.name).isEqualTo("HDFC Top 100 Fund - Direct Plan - Growth");
        assertThat(hdfc.isin).isEqualTo("INF179K01YZ4");
        assertThat(hdfc.openingUnits).isEqualByComparingTo("10");
        assertThat(hdfc.txns).hasSize(2); // stamp duty is not a transaction
        assertThat(hdfc.txns.get(0).type).isEqualTo("SIP");
        assertThat(hdfc.txns.get(0).units).isEqualByComparingTo("50.123");
        assertThat(hdfc.txns.get(1).type).isEqualTo("SELL");
        assertThat(hdfc.txns.get(1).units).isEqualByComparingTo("-20");
        assertThat(hdfc.txns.get(1).amount).isEqualByComparingTo("2000");
        assertThat(hdfc.closingNav).isEqualByComparingTo("120.50");
        assertThat(r.unparsed).isEmpty();
    }

    @Test
    void unitsReconcileAgainstPrintedClosingBalance() {
        CasParser.Result r = CasParser.parse(CAS);
        assertThat(r.schemes.get(0).unitMismatch().abs()).isLessThan(new BigDecimal("0.001"));
        // Axis: 0 + 200 = 200, but the statement prints 200.500, so something is missing and we say so.
        assertThat(r.schemes.get(1).unitMismatch()).isEqualByComparingTo("0.5");
        assertThat(r.schemes.get(1).txns.get(1).type).isEqualTo("DIVIDEND");
    }

    @Test
    void linesItCannotPlaceAreReportedNotDropped() {
        CasParser.Result r = CasParser.parse(CAS.replace("Redemption", "Mystery movement"));
        assertThat(r.unparsed).hasSize(1);
        assertThat(r.unparsed.get(0)).contains("Mystery movement");
    }
}
