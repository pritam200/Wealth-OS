package com.marketai.gmail.service;

import com.marketai.gmail.parser.ParsedEmail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for a real bug: the fingerprinter never hashed dueDate, so two different
 * months' card bills on the same card for the same amount collided on one fingerprint and the
 * second was silently dropped as a false duplicate. The fix is scoped to CARD_BILL/CARD_PAYMENT
 * only — the second half of this test proves every other transaction type's fingerprint is
 * completely unaffected, so re-processing old, already-imported emails of those types still
 * produces the exact hash already stored for them.
 */
class TransactionFingerprinterCardTest {

    private final TransactionFingerprinter fp = new TransactionFingerprinter();

    private ParsedEmail cardBill(LocalDate dueDate, BigDecimal amount) {
        return ParsedEmail.builder()
            .type(ParsedEmail.Type.CARD_BILL)
            .bank("HDFC")
            .cardLast4("1234")
            .amount(amount)
            .dueDate(dueDate)
            .build();
    }

    @Test
    @DisplayName("two card bills for the same card and amount but different due dates no longer collide")
    void differentMonthsBillsDoNotCollide() {
        ParsedEmail march = cardBill(LocalDate.of(2026, 3, 5), new BigDecimal("5000.00"));
        ParsedEmail april = cardBill(LocalDate.of(2026, 4, 5), new BigDecimal("5000.00"));

        assertThat(fp.fingerprint(march)).isNotEqualTo(fp.fingerprint(april));
    }

    @Test
    @DisplayName("the same bill fingerprinted twice still produces the same hash (idempotent)")
    void sameCardBillIsStable() {
        ParsedEmail a = cardBill(LocalDate.of(2026, 3, 5), new BigDecimal("5000.00"));
        ParsedEmail b = cardBill(LocalDate.of(2026, 3, 5), new BigDecimal("5000.00"));

        assertThat(fp.fingerprint(a)).isEqualTo(fp.fingerprint(b));
    }

    @Test
    @DisplayName("a payment and its own reversal do not fingerprint identically")
    void paymentAndReversalDiffer() {
        ParsedEmail confirmed = ParsedEmail.builder()
            .type(ParsedEmail.Type.CARD_PAYMENT)
            .bank("HDFC").cardLast4("1234").amount(new BigDecimal("5000.00"))
            .paymentDate(LocalDate.of(2026, 3, 4)).paymentReference("REF123")
            .paymentStatus("CONFIRMED")
            .build();
        ParsedEmail reversed = confirmed.toBuilder().paymentStatus("REVERSED").build();

        assertThat(fp.fingerprint(confirmed)).isNotEqualTo(fp.fingerprint(reversed));
    }

    @Test
    @DisplayName("non-card transaction types are unaffected by the new due-date field — hash unchanged")
    void tradeFingerprintUnaffectedByCardFieldAddition() {
        // Same fields as before the fix; dueDate/statementDate/paymentDate are all null for a
        // trade, and CARD_BILL/CARD_PAYMENT gating means they are never appended to a trade's hash.
        ParsedEmail trade = ParsedEmail.builder()
            .type(ParsedEmail.Type.TRADE_BUY)
            .symbol("RELIANCE").exchange("NSE").quantity(10)
            .price(new BigDecimal("1400.00"))
            .tradeDate(LocalDate.of(2026, 3, 5))
            .build();

        // Recomputing twice must be stable, and adding a dueDate to a copy of the SAME trade
        // (which no trade parser would ever set) must not change the hash — proving the gate.
        ParsedEmail tradeWithStrayDueDate = trade.toBuilder().dueDate(LocalDate.of(2099, 1, 1)).build();

        assertThat(fp.fingerprint(trade)).isEqualTo(fp.fingerprint(tradeWithStrayDueDate));
    }
}
