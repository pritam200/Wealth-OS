package com.marketai.gmail.entity;

/**
 * Explicit outcome of weighted duplicate matching for one imported financial record. Distinct
 * from the exact-hash/exact-reference gates in {@code ParsedEmailImporter} (those are a separate,
 * fully deterministic "identical content" check that runs first) — this is the fuzzy,
 * multi-factor layer for two documents describing what is probably, but not provably, the same
 * real-world transaction.
 *
 * <p>{@link TransactionMatchScorer} only ever assigns three of these — {@link #NEW},
 * {@link #NEEDS_REVIEW}, {@link #MATCHED_TO_EXISTING} — automatically, based on score thresholds.
 * The other three exist for a later human/reconciliation workflow that acts on a NEEDS_REVIEW
 * item; nothing in this slice writes them yet.
 */
public enum DuplicateState {
    /** No existing record scored high enough to be considered a match. Imported normally. */
    NEW,

    /**
     * Scored above the review threshold but below the confirm threshold. Per spec, an uncertain
     * transaction is never silently discarded — it is imported AND flagged, so a human (or a
     * later reconciliation pass) can confirm or reject the match.
     */
    NEEDS_REVIEW,

    /** A human/automated reviewer looked at a NEEDS_REVIEW pair and believes it is a duplicate. */
    POSSIBLE_DUPLICATE,

    /** A human/automated reviewer is certain a NEEDS_REVIEW (or POSSIBLE_DUPLICATE) pair is one. */
    CONFIRMED_DUPLICATE,

    /**
     * Scored at or above the confirm threshold against an existing row at import time. The
     * candidate is NOT persisted a second time — it is treated as further corroboration of the
     * record already on file, not a new financial event.
     */
    MATCHED_TO_EXISTING,

    /** A matched or confirmed-duplicate pair that has since been explicitly reconciled. */
    RECONCILED
}
