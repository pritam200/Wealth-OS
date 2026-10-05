package com.marketai.dataplatform.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketai.dataplatform.domain.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NormalizersTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AaFiNormalizer aa = new AaFiNormalizer(mapper);
    private final EmailEventNormalizer email = new EmailEventNormalizer(mapper);
    private final StatementRowNormalizer statement = new StatementRowNormalizer(mapper);

    private static RawRecord raw(String schema, String json) { return new RawRecord(RecordKind.TRANSACTION, null, "r", json, schema); }

    @Test @DisplayName("AA: a mutual-fund purchase becomes a BUY with figures completed arithmetically, never invented")
    void aaBuy() {
        var out = aa.transactions(raw("aa-fi-v1", "{\"fiType\":\"MUTUAL_FUNDS\",\"institution\":\"HDFC MF\",\"accountId\":\"F1\",\"txn\":{\"txnId\":\"T1\","
            + "\"type\":\"BUY\",\"date\":\"2026-08-15\",\"isin\":\"INF1\",\"name\":\"Flexi\",\"units\":\"50\",\"nav\":\"200\"}}"), SourceType.ACCOUNT_AGGREGATOR, "aa");
        assertThat(out).singleElement().satisfies(t -> {
            assertThat(t.getType()).isEqualTo(TransactionType.BUY);
            assertThat(t.getTransactionDate()).isEqualTo(LocalDate.of(2026, 8, 15));
            assertThat(t.getNetAmount()).isEqualByComparingTo("10000");       // units × NAV, derived
            assertThat(t.getSourceReference()).isEqualTo("T1");
            assertThat(t.getSourceType()).isEqualTo(SourceType.ACCOUNT_AGGREGATOR);
        });
    }

    @Test @DisplayName("AA: a deposit-account credit is a cash movement with no asset")
    void aaDeposit() {
        var out = aa.transactions(raw("aa-fi-v1", "{\"fiType\":\"DEPOSIT\",\"institution\":\"HDFC Bank\",\"accountId\":\"123456789012\",\"txn\":{\"txnId\":\"U1\","
            + "\"type\":\"CREDIT\",\"date\":\"2026-08-15\",\"amount\":\"500\"}}"), SourceType.ACCOUNT_AGGREGATOR, "aa");
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getAsset()).isNull();
    }

    @Test @DisplayName("AA: an unknown transaction type is an error, not a guess")
    void aaUnknownType() {
        assertThatThrownBy(() -> aa.transactions(raw("aa-fi-v1", "{\"fiType\":\"MUTUAL_FUNDS\",\"institution\":\"X\",\"txn\":{\"type\":\"TELEPORT\",\"date\":\"2026-01-01\"}}"),
            SourceType.ACCOUNT_AGGREGATOR, "aa")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test @DisplayName("AA: invalid JSON is rejected with a plain message that does not echo the payload")
    void aaInvalidJson() {
        assertThatThrownBy(() -> aa.transactions(raw("aa-fi-v1", "{not json SECRET-123"), SourceType.ACCOUNT_AGGREGATOR, "aa"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("SECRET");
    }

    @Test @DisplayName("AA: a holding record yields a holding, not a transaction")
    void aaHolding() {
        RawRecord r = new RawRecord(RecordKind.HOLDING, "F1", "H1", "{\"fiType\":\"MUTUAL_FUNDS\",\"institution\":\"HDFC MF\",\"accountId\":\"F1\","
            + "\"holding\":{\"isin\":\"INF1\",\"name\":\"Flexi\",\"units\":\"100\",\"asOf\":\"2026-10-05\"}}", "aa-fi-v1");
        assertThat(aa.transactions(r, SourceType.ACCOUNT_AGGREGATOR, "aa")).isEmpty();
        assertThat(aa.holdings(r, SourceType.ACCOUNT_AGGREGATOR, "aa")).singleElement()
            .satisfies(h -> assertThat(h.getQuantity()).isEqualByComparingTo("100"));
    }

    @Test @DisplayName("email: only investment events map; spending and card items are outside the ledger")
    void emailScope() {
        assertThat(email.transactions(raw("email-parsed-v1", "{\"type\":\"CARD_PURCHASE\",\"amount\":\"500\"}"), SourceType.EMAIL, "gmail")).isEmpty();
        assertThat(email.transactions(raw("email-parsed-v1", "{\"type\":\"EXPENSE\",\"amount\":\"500\"}"), SourceType.EMAIL, "gmail")).isEmpty();
    }

    @Test @DisplayName("email: a SIP confirmation is a SIP at email confidence; a switch leg keeps its link group")
    void emailSipAndSwitch() {
        var sip = email.transactions(raw("email-parsed-v1", "{\"type\":\"MF_SIP\",\"fundName\":\"Flexi\",\"isin\":\"INF1\",\"amount\":\"10000\","
            + "\"tradeDate\":\"2026-08-15\",\"provider\":\"HDFC MF\",\"sourceDescription\":\"Your SIP was processed\",\"extractionConfidence\":\"0.8\"}"), SourceType.EMAIL, "gmail");
        assertThat(sip).singleElement().satisfies(t -> {
            assertThat(t.getType()).isEqualTo(TransactionType.SIP);
            assertThat(t.getSourceType()).isEqualTo(SourceType.EMAIL);
            assertThat(t.getRecordConfidence()).isEqualTo(0.8);
        });
        var out = email.transactions(raw("email-parsed-v1", "{\"type\":\"MF_REDEEM\",\"fundName\":\"A\",\"isin\":\"INF1\",\"amount\":\"100\","
            + "\"tradeDate\":\"2026-08-15\",\"linkGroup\":\"SW1\"}"), SourceType.EMAIL, "gmail");
        assertThat(out.get(0).getType()).isEqualTo(TransactionType.SWITCH_OUT);
        assertThat(out.get(0).getLinkGroup()).isEqualTo("SW1");
    }

    @Test @DisplayName("email: a stock trade needs no price to be recorded; units are derived from amount ÷ NAV only when both are stated")
    void emailDerivations() {
        var mf = email.transactions(raw("email-parsed-v1", "{\"type\":\"MF_SIP\",\"fundName\":\"A\",\"isin\":\"INF1\",\"amount\":\"1000\",\"nav\":\"20\","
            + "\"tradeDate\":\"2026-08-15\"}"), SourceType.EMAIL, "gmail");
        assertThat(mf.get(0).getQuantity()).isEqualByComparingTo("50");
        var noNav = email.transactions(raw("email-parsed-v1", "{\"type\":\"MF_SIP\",\"fundName\":\"A\",\"isin\":\"INF1\",\"amount\":\"1000\","
            + "\"tradeDate\":\"2026-08-15\"}"), SourceType.EMAIL, "gmail");
        assertThat(noNav.get(0).getQuantity()).isNull();     // not guessed
    }

    @Test @DisplayName("statement: a row maps its columns, and a dividend reinvestment is understood as the unit purchase it is")
    void statementRow() {
        var out = statement.transactions(raw("statement-row-v1", "{\"institution\":\"HDFC MF\",\"accountId\":\"F1\",\"assetClass\":\"MUTUAL_FUND\","
            + "\"type\":\"DIVIDEND_REINVESTMENT\",\"date\":\"2026-05-10\",\"isin\":\"INF1\",\"name\":\"A\",\"quantity\":\"2\",\"price\":\"10\",\"amount\":\"20\"}"), SourceType.STATEMENT, "csv");
        assertThat(out).singleElement().satisfies(t -> {
            assertThat(t.getType()).isEqualTo(TransactionType.BUY);   // a reinvestment is a unit purchase
            assertThat(t.getQuantity()).isEqualByComparingTo("2");
        });
    }

    @Test @DisplayName("statement: day-first dates and Indian number formatting are read correctly")
    void statementFormats() {
        var out = statement.transactions(raw("statement-row-v1", "{\"institution\":\"X\",\"assetClass\":\"STOCK\",\"symbol\":\"INFY\","
            + "\"type\":\"BUY\",\"date\":\"15/08/2026\",\"quantity\":\"1,000\",\"price\":\"1,450.50\"}"), SourceType.STATEMENT, "csv");
        assertThat(out.get(0).getTransactionDate()).isEqualTo(LocalDate.of(2026, 8, 15));
        assertThat(out.get(0).getQuantity()).isEqualByComparingTo("1000");
        assertThat(out.get(0).getUnitPrice()).isEqualByComparingTo("1450.50");
    }

    @Test @DisplayName("each normaliser claims only its own schema")
    void supportsOnlyOwnSchema() {
        assertThat(aa.supports("aa-fi-v1")).isTrue();
        assertThat(aa.supports("email-parsed-v1")).isFalse();
        assertThat(email.supports("email-parsed-v1")).isTrue();
        assertThat(statement.supports("statement-row-v1")).isTrue();
        assertThat(statement.supports(null)).isFalse();
    }
}
