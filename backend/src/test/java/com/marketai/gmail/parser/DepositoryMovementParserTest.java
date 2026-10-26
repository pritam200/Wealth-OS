package com.marketai.gmail.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** CDSL's notice as it arrives, with the account and holdings made up. */
class DepositoryMovementParserTest {

    static final String NOTICE = """
        Transactions In Your Demat Account
        Dear MR TEST, Following is/are the list of transactions for your Demat account ending with *12345678
        Sr. No. | Company Name | ISIN | Quantity | Debit / Credit | Date and Time
        1 | LARSEN & TOUBRO LIMITED-EQUITY SHARES OF RS.2/- EACH | INE018A01030 | 4.000 | Credit | 30/09/2026 12:55:00
        2 | INFOSYS LIMITED-EQUITY SHARES OF RS.5/- EACH | INE009A01021 | 10.000 | Debit | 01/10/2026 08:41:12
        For further queries, please contact your Depository Participant [DP] with whom you hold your demat account.
        """;

    @Test
    @DisplayName("every row is read, with only the account's last four digits kept")
    void readsEveryRow() {
        var n = DepositoryMovementParser.parse(NOTICE);
        assertThat(n.accountSuffix()).isEqualTo("5678");
        assertThat(n.movements()).hasSize(2);
        var first = n.movements().get(0);
        assertThat(first.isin()).isEqualTo("INE018A01030");
        assertThat(first.quantity()).isEqualByComparingTo("4");
        assertThat(first.credit()).isTrue();
        assertThat(first.movedAt()).isEqualTo(LocalDateTime.of(2026, 9, 30, 12, 55));
        assertThat(n.movements().get(1).credit()).isFalse();
    }

    @Test
    @DisplayName("text that isn't the notice, or a row it can't read, gives nothing")
    void refusesOtherText() {
        assertThat(DepositoryMovementParser.parse("Your OTP is 123456")).isNull();
        assertThat(DepositoryMovementParser.parse(NOTICE.replace("12:55:00", "12:55"))).as("one row in a layout it doesn't know").isNull();
        assertThat(DepositoryMovementParser.parse(NOTICE.replaceAll("(?m)^\\d+ \\|.*$", ""))).isNull();
    }
}
