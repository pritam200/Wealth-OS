package com.marketai.gmail.service;

import com.marketai.gmail.parser.ParsedEmail;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;

/**
 * SHA-256 fingerprint of a parsed transaction's identifying content, so the same real-world
 * transaction hashes identically no matter which email/parser/PDF delivered it.
 *
 * Only fields intrinsic to the transaction are hashed — never the Gmail message id, sender, or
 * free-text description, all of which differ between an original and a forwarded/resent copy of
 * the same alert. Amounts are normalised to 2dp so "1000" and "1000.00" agree.
 */
@Component
public class TransactionFingerprinter {

    public String fingerprint(ParsedEmail pe) {
        StringBuilder sb = new StringBuilder();
        sb.append(pe.getType() != null ? pe.getType().name() : "UNKNOWN");
        // The transaction's own date, under whichever field the parser populated for this type.
        sb.append('|').append(norm(pe.getTradeDate() != null ? pe.getTradeDate() : pe.getStartDate()));
        sb.append('|').append(norm(pe.getAmount()));
        sb.append('|').append(norm(pe.getPrice()));
        sb.append('|').append(norm(pe.getPrincipal()));
        sb.append('|').append(norm(pe.getMonthlyAmount()));
        sb.append('|').append(norm(pe.getUnits()));
        sb.append('|').append(pe.getQuantity() != null ? pe.getQuantity().toString() : "");
        sb.append('|').append(key(pe.getSymbol()));
        sb.append('|').append(key(pe.getFolio()));
        sb.append('|').append(key(pe.getFundName()));
        sb.append('|').append(key(pe.getBank()));
        sb.append('|').append(key(pe.getMerchant()));
        sb.append('|').append(key(pe.getCardLast4()));

        // CARD_BILL/CARD_PAYMENT only, appended after every pre-existing field so the hash for
        // every other transaction type is byte-for-byte unchanged (re-processing an old email
        // must still produce the fingerprint already stored for it, or dedup silently breaks for
        // all pre-existing data). Without this, two different months' bills on the same card for
        // the same total-due amount collide on one fingerprint and the second is dropped as a
        // false duplicate — dueDate/statementDate distinguish them; referenceNumber/paymentDate/
        // paymentStatus do the same for payment confirmations (a reversal must not fingerprint
        // identically to the original payment it reverses).
        if (pe.getType() == ParsedEmail.Type.CARD_BILL || pe.getType() == ParsedEmail.Type.CARD_PAYMENT) {
            sb.append('|').append(norm(pe.getDueDate()));
            sb.append('|').append(norm(pe.getStatementDate()));
            sb.append('|').append(norm(pe.getPaymentDate()));
            sb.append('|').append(key(pe.getPaymentReference()));
            sb.append('|').append(key(pe.getPaymentStatus()));
        }
        // Appended only when non-zero so every first occurrence keeps its existing hash.
        if (pe.getOccurrenceInSource() > 0) {
            sb.append("|#").append(pe.getOccurrenceInSource());
        }
        return sha256(sb.toString());
    }

    /**
     * A distinct fingerprint for a transaction whose content is identical to one already recorded
     * but which is known to be separate (a different rail reference, or a person confirmed it).
     * Stable for the same qualifier, so re-reading that document is still recognised.
     */
    public String qualified(String fingerprint, String qualifier) {
        return sha256(fingerprint + "|" + qualifier);
    }

    private static String norm(BigDecimal v) {
        return v == null ? "" : v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String norm(LocalDate d) {
        return d == null ? "" : d.toString();
    }

    // Case/whitespace-insensitive so "HDFC Bank" and "hdfc  bank" don't fingerprint differently.
    private static String key(String s) {
        return s == null ? "" : s.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) hex.append('0');
                hex.append(h);
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JCA spec on every JVM — unreachable in practice.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
