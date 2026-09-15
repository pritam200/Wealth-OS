package com.marketai.document.identity;

/**
 * Outcome of resolving one incoming event against what is already recorded.
 *
 * @param outcome   the tier that decided it
 * @param score     0–100 confidence. Exact tiers score 100; tolerant and review tiers carry a
 *                  computed score so the banding is inspectable rather than implicit.
 * @param matchedId identifier of the event matched against, null when {@link MatchOutcome#NEW}
 * @param detail    why — safe to log and to show a user
 */
public record MatchResult(MatchOutcome outcome, int score, String matchedId, String detail) {

    public static MatchResult exactReference(String matchedId, ExternalReference ref) {
        return new MatchResult(MatchOutcome.EXACT_REFERENCE, 100, matchedId,
            "Matched on " + ref.type() + " " + ref.value() + " — a rail-issued reference "
                + "identifies exactly one payment");
    }

    public static MatchResult compositeKey(String matchedId) {
        return new MatchResult(MatchOutcome.COMPOSITE_KEY, 100, matchedId,
            "Account, instrument, direction, quantity, amount and date all agree exactly");
    }

    public static MatchResult tolerant(String matchedId, int score, String detail) {
        return new MatchResult(MatchOutcome.TOLERANT, score, matchedId, detail);
    }

    public static MatchResult needsReview(String matchedId, int score, String detail) {
        return new MatchResult(MatchOutcome.NEEDS_REVIEW, score, matchedId, detail);
    }

    public static MatchResult isNew(String detail) {
        return new MatchResult(MatchOutcome.NEW, 0, null, detail);
    }

    public boolean isDuplicate() { return outcome.isDuplicate(); }
}
