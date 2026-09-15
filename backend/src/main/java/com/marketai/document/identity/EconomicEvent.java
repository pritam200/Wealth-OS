package com.marketai.document.identity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The financial facts that make a transaction what it is, stripped of how it arrived.
 *
 * <p>Deliberately excludes the Gmail message id, sender, document id and free text. Two
 * documents describing one trade — a contract-note PDF and a broker email — share none of those
 * and all of these. That asymmetry is the whole reason content hashing cannot solve economic
 * identity: the bytes differ while the event is the same.
 *
 * @param account    the account or folio this belongs to, when known
 * @param instrument symbol, scheme name, or merchant — whatever names the other side
 * @param direction  BUY/SELL/CREDIT/DEBIT
 * @param quantity   units or shares, null for cash movements
 * @param amount     the rupee value
 * @param date       the transaction's own date, not when it was notified
 * @param references rail references harvested from the document
 */
public record EconomicEvent(String account, String instrument, String direction,
                            BigDecimal quantity, BigDecimal amount, LocalDate date,
                            List<ExternalReference> references) {

    public EconomicEvent {
        references = references == null ? List.of() : List.copyOf(references);
    }

    /** Case- and whitespace-insensitive key, so "HDFC Bank" and "hdfc  bank" agree. */
    static String key(String s) {
        return s == null ? "" : s.trim().toLowerCase().replaceAll("\\s+", " ");
    }
}
