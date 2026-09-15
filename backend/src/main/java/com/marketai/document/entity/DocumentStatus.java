package com.marketai.document.entity;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of a financial document, from arrival to ledger.
 *
 * Expressed as an explicit state machine rather than a loose enum because the transitions carry
 * real correctness weight. Two in particular:
 *
 * <ul>
 *   <li>{@link #FAILED} is <em>not</em> terminal. A transient failure — a DB hiccup, a parser
 *       exception, a timeout — must not permanently block a document from ever being retried.
 *       That exact bug had already shipped in this codebase once, where a persisted FAILED row
 *       caused the "already processed" guard to skip the email forever.</li>
 *   <li>Nothing reaches {@link #IMPORTED} without passing through {@link #VALIDATED} or a human
 *       decision in {@link #REQUIRES_REVIEW}. There is no path from raw extraction straight to
 *       the ledger.</li>
 * </ul>
 */
public enum DocumentStatus {

    /** Seen, not yet touched. The only legal entry point. */
    DISCOVERED,

    /** Being worked on — fetched, decrypted, text extracted. */
    PROCESSING,

    /** An encrypted PDF was successfully opened. Recorded separately because which candidate
     *  password worked is diagnostic information worth keeping. */
    PASSWORD_RESOLVED,

    /** Structured values pulled out. Not yet checked — parsing is not believing. */
    PARSED,

    /** Extraction passed its checks: arithmetic reconciles and every value's source span was
     *  located in the document. */
    VALIDATED,

    /** Written to the ledger. Terminal. */
    IMPORTED,

    /** This economic event is already recorded, from this or another source. Terminal, and
     *  deliberately distinct from IMPORTED so "we saw it and skipped it" is not confused with
     *  "we never saw it". */
    DUPLICATE,

    /** Multiple defensible readings exist. Always routes to a human — never resolved by
     *  picking the highest-scoring interpretation. */
    AMBIGUOUS,

    /** Could not be processed. Retryable: see the class comment. */
    FAILED,

    /** Waiting on a human decision. No ledger row exists yet. */
    REQUIRES_REVIEW;

    private static final Set<DocumentStatus> TERMINAL = EnumSet.of(IMPORTED, DUPLICATE);

    /** True when no further transition is expected. FAILED is deliberately absent. */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** True when a human must act before this document can progress. */
    public boolean awaitsHuman() {
        return this == REQUIRES_REVIEW || this == AMBIGUOUS;
    }

    /**
     * Whether this document has produced, or could still produce, a ledger row. Used to decide
     * whether an incoming duplicate of the same artifact should be reprocessed.
     */
    public boolean blocksReprocessing() {
        return this == IMPORTED || this == DUPLICATE;
    }

    Set<DocumentStatus> allowedNext() {
        return switch (this) {
            case DISCOVERED       -> EnumSet.of(PROCESSING, FAILED);
            case PROCESSING       -> EnumSet.of(PASSWORD_RESOLVED, PARSED, FAILED, REQUIRES_REVIEW);
            case PASSWORD_RESOLVED-> EnumSet.of(PARSED, FAILED);
            case PARSED           -> EnumSet.of(VALIDATED, AMBIGUOUS, REQUIRES_REVIEW, FAILED);
            case VALIDATED        -> EnumSet.of(IMPORTED, DUPLICATE, FAILED);
            case AMBIGUOUS        -> EnumSet.of(REQUIRES_REVIEW, FAILED);
            case REQUIRES_REVIEW  -> EnumSet.of(IMPORTED, DUPLICATE, FAILED);
            // Retry path. A failed document goes back to the start rather than resuming from
            // wherever it died, because partial state from a failed run cannot be trusted.
            case FAILED           -> EnumSet.of(DISCOVERED);
            case IMPORTED, DUPLICATE -> EnumSet.noneOf(DocumentStatus.class);
        };
    }

    public boolean canTransitionTo(DocumentStatus next) {
        return next != null && allowedNext().contains(next);
    }
}
