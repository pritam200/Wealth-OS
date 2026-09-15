package com.marketai.document.identity;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decides whether an incoming event is one already recorded.
 *
 * <p>Three tiers, tried in order, each weaker than the last:
 *
 * <ol>
 *   <li><b>Exact reference</b> — a globally-unique rail reference (UTR, RRN, UPI txn id, IMPS
 *       ref) matches. Decisive, and the reason harvesting references is worth the effort: it
 *       removes the guesswork entirely.</li>
 *   <li><b>Composite natural key</b> — account, instrument, direction, quantity, amount and
 *       date, first exactly, then within tolerance.</li>
 *   <li><b>Scored similarity</b> — close enough for a human to look at, never close enough to
 *       act on automatically.</li>
 * </ol>
 *
 * <p>The asymmetry between the two failure modes drives every threshold here. Failing to spot a
 * duplicate books a transaction twice, which reconciliation will surface and a user can correct.
 * Wrongly declaring a duplicate <em>discards a real transaction silently</em> — nothing reports
 * it, because as far as the system is concerned nothing happened. So the tiers are tuned to
 * prefer a visible double-booking over an invisible loss, and anything uncertain goes to a
 * human rather than being resolved by picking the higher score.
 */
@Component
public class EconomicIdentityResolver {

    /**
     * Amounts must agree to within a rupee. Wide enough for the paise rounding that genuinely
     * differs between a broker's email and its own contract note; far too narrow to absorb a
     * misread digit or a genuinely different transaction.
     */
    static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("1.00");

    /**
     * Dates may differ by up to this many days. Settlement and value dates legitimately diverge
     * from trade dates across documents describing one event.
     */
    static final int DATE_TOLERANCE_DAYS = 3;

    /** At or above this score a tolerant match is treated as a duplicate. */
    static final int AUTO_MATCH_SCORE = 85;

    /** Between this and {@link #AUTO_MATCH_SCORE}, a human decides. Below it, the event is new. */
    static final int REVIEW_SCORE = 70;

    /**
     * @param incoming the event just extracted
     * @param existing already-recorded events, keyed by their identifier
     */
    public MatchResult resolve(EconomicEvent incoming, Map<String, EconomicEvent> existing) {
        if (incoming == null) {
            return MatchResult.isNew("No incoming event to resolve");
        }
        if (existing == null || existing.isEmpty()) {
            return MatchResult.isNew("Nothing recorded to compare against");
        }

        // --- Tier 1: a rail-issued reference is decisive.
        Optional<MatchResult> byReference = matchByReference(incoming, existing);
        if (byReference.isPresent()) return byReference.get();

        // --- Tier 2 and 3: composite key, exact then tolerant, then scored.
        String bestId = null;
        int bestScore = -1;
        String bestDetail = null;

        for (Map.Entry<String, EconomicEvent> e : existing.entrySet()) {
            EconomicEvent candidate = e.getValue();

            if (exactComposite(incoming, candidate)) {
                return MatchResult.compositeKey(e.getKey());
            }
            if (differsOnlyByAmount(incoming, candidate)) {
                // A named ambiguity rather than a score. When account, instrument, direction,
                // quantity and date all agree and only the amount differs, the two most likely
                // explanations are opposites:
                //
                //   - one document includes brokerage/charges and the other does not (a bank
                //     debit against a contract note's net amount) — the same event, and booking
                //     it again would corrupt the holding through ledger replay; or
                //   - two genuinely separate trades of the same quantity on the same day at
                //     different prices.
                //
                // Nothing in the data distinguishes them, so this is escalated by name instead
                // of being decided by whichever way a weighted score happens to fall.
                return MatchResult.needsReview(e.getKey(), 60, String.format(
                    "Identical except for amount (₹%s vs ₹%s) — could be the same trade with "
                        + "charges included, or a separate trade of the same size that day",
                    incoming.amount() == null ? "?" : incoming.amount().toPlainString(),
                    candidate.amount() == null ? "?" : candidate.amount().toPlainString()));
            }
            Scored scored = score(incoming, candidate);
            if (scored.score() > bestScore) {
                bestScore = scored.score();
                bestId = e.getKey();
                bestDetail = scored.detail();
            }
        }

        if (bestScore >= AUTO_MATCH_SCORE) {
            return MatchResult.tolerant(bestId, bestScore, bestDetail);
        }
        if (bestScore >= REVIEW_SCORE) {
            // Not resolved by taking the higher score. A human is asked, because acting either
            // way on this evidence risks the loss described in the class comment.
            return MatchResult.needsReview(bestId, bestScore,
                bestDetail + " — too similar to book blindly, too different to merge");
        }
        return MatchResult.isNew("No recorded event scored above " + REVIEW_SCORE
            + " (best was " + Math.max(bestScore, 0) + ")");
    }

    private Optional<MatchResult> matchByReference(EconomicEvent incoming,
                                                   Map<String, EconomicEvent> existing) {
        List<ExternalReference> incomingRefs = incoming.references().stream()
            .filter(ExternalReference::isGloballyUnique)
            .toList();
        if (incomingRefs.isEmpty()) return Optional.empty();

        for (Map.Entry<String, EconomicEvent> e : existing.entrySet()) {
            for (ExternalReference mine : incomingRefs) {
                boolean hit = e.getValue().references().stream()
                    .filter(ExternalReference::isGloballyUnique)
                    .anyMatch(theirs -> theirs.type() == mine.type()
                                     && theirs.value().equals(mine.value()));
                if (hit) return Optional.of(MatchResult.exactReference(e.getKey(), mine));
            }
        }
        return Optional.empty();
    }

    private boolean exactComposite(EconomicEvent a, EconomicEvent b) {
        return EconomicEvent.key(a.account()).equals(EconomicEvent.key(b.account()))
            && EconomicEvent.key(a.instrument()).equals(EconomicEvent.key(b.instrument()))
            && EconomicEvent.key(a.direction()).equals(EconomicEvent.key(b.direction()))
            && eq(a.quantity(), b.quantity())
            && eq(a.amount(), b.amount())
            && java.util.Objects.equals(a.date(), b.date());
    }

    /**
     * True when every component of the composite key agrees except the amount.
     *
     * <p>Requires a real amount on both sides and a non-blank instrument — otherwise two sparse
     * events with mostly-null fields would satisfy it vacuously and be escalated for no reason.
     */
    private boolean differsOnlyByAmount(EconomicEvent a, EconomicEvent b) {
        if (a.amount() == null || b.amount() == null) return false;
        if (eq(a.amount(), b.amount())) return false;
        // A sub-rupee gap is rounding, not a charges difference. Leave it to the tolerant tier
        // rather than escalating a match we can make confidently.
        if (withinAmountTolerance(a.amount(), b.amount())) return false;
        if (EconomicEvent.key(a.instrument()).isEmpty()) return false;
        if (a.date() == null || b.date() == null) return false;

        return EconomicEvent.key(a.account()).equals(EconomicEvent.key(b.account()))
            && EconomicEvent.key(a.instrument()).equals(EconomicEvent.key(b.instrument()))
            && EconomicEvent.key(a.direction()).equals(EconomicEvent.key(b.direction()))
            && eq(a.quantity(), b.quantity())
            && a.date().equals(b.date());
    }

    private record Scored(int score, String detail) {}

    /**
     * Weighted similarity over the composite key.
     *
     * <p>Direction is a hard gate rather than a weight: a BUY and a SELL of the same quantity on
     * the same day are opposites, not near-duplicates, and no amount of agreement elsewhere
     * should be able to average them together.
     */
    private Scored score(EconomicEvent a, EconomicEvent b) {
        if (!EconomicEvent.key(a.direction()).equals(EconomicEvent.key(b.direction()))) {
            return new Scored(0, "Opposite direction — not the same event");
        }

        int score = 0;
        StringBuilder why = new StringBuilder();

        // Amount, 45 points — the most identifying field in practice.
        if (eq(a.amount(), b.amount())) {
            score += 45;
            why.append("amount identical");
        } else if (withinAmountTolerance(a.amount(), b.amount())) {
            score += 38;
            why.append("amount within ₹").append(AMOUNT_TOLERANCE.toPlainString());
        } else {
            why.append("amount differs");
        }

        // Instrument, 25 points.
        if (!EconomicEvent.key(a.instrument()).isEmpty()
                && EconomicEvent.key(a.instrument()).equals(EconomicEvent.key(b.instrument()))) {
            score += 25;
            why.append("; instrument matches");
        } else {
            why.append("; instrument differs");
        }

        // Date, 20 points, degrading with distance.
        long days = a.date() == null || b.date() == null
            ? Long.MAX_VALUE : Math.abs(ChronoUnit.DAYS.between(a.date(), b.date()));
        if (days == 0) {
            score += 20;
            why.append("; same date");
        } else if (days <= DATE_TOLERANCE_DAYS) {
            score += 20 - (int) days * 3;
            why.append("; date ").append(days).append("d apart");
        } else {
            why.append("; date ").append(days == Long.MAX_VALUE ? "unknown" : days + "d").append(" apart");
        }

        // Quantity, 10 points. Absent on both sides is agreement, not a mismatch — cash
        // movements have no quantity and should not be penalised for it.
        if (a.quantity() == null && b.quantity() == null) {
            score += 10;
        } else if (eq(a.quantity(), b.quantity())) {
            score += 10;
            why.append("; quantity matches");
        } else {
            why.append("; quantity differs");
        }

        return new Scored(Math.min(score, 100), why.toString());
    }

    private boolean withinAmountTolerance(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return false;
        return a.subtract(b).abs().compareTo(AMOUNT_TOLERANCE) <= 0;
    }

    private static boolean eq(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.compareTo(b) == 0;
    }
}
