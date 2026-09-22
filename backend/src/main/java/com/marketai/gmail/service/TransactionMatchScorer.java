package com.marketai.gmail.service;

import com.marketai.gmail.entity.ImportedTransactionFingerprint;
import com.marketai.gmail.parser.ParsedEmail;
import com.marketai.gmail.repository.ImportedTransactionFingerprintRepository;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Weighted, multi-factor duplicate scoring — separate from (and running after) the two fully
 * deterministic gates already in {@link ParsedEmailImporter}: an exact rail-reference match and
 * an exact SHA-256 content-fingerprint match. Those two only ever fire on byte-for-byte identical
 * evidence; this exists for the much more common case the spec calls out explicitly — the SAME
 * real-world transaction described slightly differently by two documents (a transaction alert's
 * "₹4,500 at Amazon on 10 Sep" vs a statement line "10 Sep | AMAZON | ₹4,500.00"), which hash
 * differently but should not both become ledger entries.
 *
 * <p><b>Why amount + date + merchant is not enough on its own:</b> a user can legitimately spend
 * ₹5,000 at Amazon twice in one day. No single signal is decisive here (unlike the rail
 * reference, which structurally identifies one payment) — that is exactly why this is a weighted
 * score with an uncertain middle band, rather than a boolean.
 *
 * <p>Deliberately scoped to a fixed list of {@code ParsedEmail.Type}s ({@link #SCORED_TYPES}) —
 * trades, FDs, RDs and MF transactions already have their own dedicated, field-appropriate dedup
 * checks inside {@code ParsedEmailImporter.routeImport}, keyed on quantity/units/principal rather
 * than merchant, and are left untouched.
 *
 * <p>The weights below are a documented starting point, not a proven-optimal split — per the
 * spec, they are meant to be tuned experimentally as real mismatches are observed, which is why
 * each is a named constant rather than inlined into the arithmetic.
 */
@Component
@RequiredArgsConstructor
public class TransactionMatchScorer {

    public static final List<ParsedEmail.Type> SCORED_TYPES = List.of(
        ParsedEmail.Type.EXPENSE, ParsedEmail.Type.INCOME, ParsedEmail.Type.DIVIDEND,
        ParsedEmail.Type.CARD_BILL, ParsedEmail.Type.CARD_PAYMENT);

    // Calibrated so that "amount + same day + same card + exact merchant" (the common
    // alert-vs-statement-line case, with no rail reference available) clears CONFIRM_THRESHOLD
    // on its own, while "amount + exact merchant" alone — the ambiguous case where a user could
    // have legitimately spent the same amount at the same merchant twice — stays below
    // REVIEW_THRESHOLD. External-reference match dominates because, when present, it is nearly
    // as decisive as the tier-1 exact gate; the only reason a reference lands here instead of
    // there is that its type wasn't globally unique enough for tier-1's stricter bar.
    static final double W_EXTERNAL_REF = 0.35;
    static final double W_AMOUNT = 0.15;      // guaranteed to contribute — candidates are pre-filtered by exact amount
    static final double W_SAME_DAY_DATE = 0.20;
    static final double W_NEAR_DATE = 0.10;   // within NEAR_DATE_WINDOW_DAYS but not same day
    static final double W_CARD = 0.20;
    static final double W_MERCHANT_EXACT = 0.15;
    static final double W_MERCHANT_PARTIAL = 0.08;

    private static final int NEAR_DATE_WINDOW_DAYS = 3;

    /** At or above this score, the candidate is treated as the existing record, not a new one. */
    public static final double CONFIRM_THRESHOLD = 0.65;
    /** At or above this (but below CONFIRM_THRESHOLD), the candidate is imported but flagged. */
    public static final double REVIEW_THRESHOLD = 0.35;

    private final ImportedTransactionFingerprintRepository fingerprintRepo;

    @Value
    public static class ScoredMatch {
        ImportedTransactionFingerprint matched;
        double confidence;
    }

    /**
     * @return the highest-scoring prior record for this user/amount, if any scored at least
     *         {@link #REVIEW_THRESHOLD}. Callers below that bar should treat the candidate as NEW.
     */
    public Optional<ScoredMatch> findBestMatch(Long userId, ParsedEmail candidate) {
        if (!SCORED_TYPES.contains(candidate.getType()) || candidate.getAmount() == null) {
            return Optional.empty();
        }

        List<ImportedTransactionFingerprint> pool =
            fingerprintRepo.findByUserIdAndAmount(userId, candidate.getAmount().setScale(2, java.math.RoundingMode.HALF_UP));

        ImportedTransactionFingerprint best = null;
        double bestScore = 0.0;
        for (ImportedTransactionFingerprint existing : pool) {
            double s = score(candidate, existing);
            if (s > bestScore) {
                bestScore = s;
                best = existing;
            }
        }

        if (best != null && bestScore >= REVIEW_THRESHOLD) {
            return Optional.of(new ScoredMatch(best, bestScore));
        }
        return Optional.empty();
    }

    double score(ParsedEmail candidate, ImportedTransactionFingerprint existing) {
        double s = 0.0;

        // Amount is always a match here — the caller already filtered the pool by exact amount —
        // but the contribution is expressed explicitly rather than assumed, so this method gives
        // the same answer whoever calls it, including a test that hands it an ad-hoc pair.
        BigDecimal candidateAmount = candidate.getAmount();
        if (candidateAmount != null && existing.getAmount() != null
                && candidateAmount.setScale(2, java.math.RoundingMode.HALF_UP)
                    .compareTo(existing.getAmount().setScale(2, java.math.RoundingMode.HALF_UP)) == 0) {
            s += W_AMOUNT;
        }

        s += scoreDate(candidateDate(candidate), existing.getTransactionDate());
        s += scoreMerchant(candidate.getMerchant(), existing.getMerchant());

        if (nonBlank(candidate.getCardLast4()) && candidate.getCardLast4().equals(existing.getCardLast4())) {
            s += W_CARD;
        }

        // Only CARD_PAYMENT carries its own rail reference on ParsedEmail today
        // (paymentReference); other types' references, when present, are harvested from raw
        // document text by ReferenceHarvester and already handled by the importer's tier-1 gate
        // before this scorer ever runs. This is simply the same signal, scored here too, for the
        // types where the reference travels on the parsed object itself.
        if (nonBlank(candidate.getPaymentReference())
                && candidate.getPaymentReference().equalsIgnoreCase(existing.getExternalRef())) {
            s += W_EXTERNAL_REF;
        }

        return s;
    }

    private double scoreDate(LocalDate candidateDate, LocalDate existingDate) {
        if (candidateDate == null || existingDate == null) return 0.0;
        long days = Math.abs(ChronoUnit.DAYS.between(candidateDate, existingDate));
        if (days == 0) return W_SAME_DAY_DATE;
        if (days <= NEAR_DATE_WINDOW_DAYS) return W_NEAR_DATE;
        return 0.0;
    }

    private double scoreMerchant(String candidateMerchant, String existingMerchant) {
        String a = normalize(candidateMerchant);
        String b = normalize(existingMerchant);
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        if (a.equals(b)) return W_MERCHANT_EXACT;
        if (a.contains(b) || b.contains(a)) return W_MERCHANT_PARTIAL;
        return 0.0;
    }

    /** The date a candidate's transaction "really" happened, whichever field the parser set. */
    static LocalDate candidateDate(ParsedEmail pe) {
        if (pe.getTradeDate() != null) return pe.getTradeDate();
        if (pe.getPaymentDate() != null) return pe.getPaymentDate();
        if (pe.getStatementDate() != null) return pe.getStatementDate();
        return pe.getDueDate();
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private static boolean nonBlank(String s) {
        return s != null && !s.isBlank();
    }
}
