package com.marketai.gmail.ledger;

/**
 * Where one financial event found in an email ended up. Every event the extractor reports, and
 * every part of an email that could not be read, ends in exactly one of these — there is no
 * "ignored" or "skipped" state, and each row carries the reason it is where it is.
 */
public enum EventState {
    /** Booked as a new record. */
    IMPORTED(false),
    /** Already on record (a resent alert, the statement line for an alert already booked). */
    DUPLICATE_OF_EXISTING(false),
    /** Needs no record, for a stated reason: a failed or cancelled payment, a line a person rejected. */
    RESOLVED(false),
    /** Read, but not safe to book automatically — waiting in the review queue. */
    REQUIRES_REVIEW(true),
    /** Something could not be read or does not add up (an unread part, a count or totals mismatch). */
    RECONCILIATION_REQUIRED(true),
    /** Booking was attempted and failed; the error is the reason. Also queued for review. */
    FAILED_WITH_REASON(true);

    private final boolean unresolved;

    EventState(boolean unresolved) { this.unresolved = unresolved; }

    /** Still needs a person (or a later sync) before the event is accounted for. */
    public boolean unresolved() { return unresolved; }

    public static java.util.List<EventState> unresolvedStates() {
        return java.util.Arrays.stream(values()).filter(EventState::unresolved).toList();
    }
}
