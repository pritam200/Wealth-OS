package com.marketai.identity.service;

/**
 * One password to try, with the reason it is being tried.
 *
 * <p>{@code value} is plaintext and lives only for the duration of an unlock attempt. It must
 * never be logged, persisted, returned from an API, or placed in an exception message —
 * {@link #toString()} is overridden to make an accidental interpolation harmless rather than a
 * credential leak, since {@code log.debug("trying {}", candidate)} is exactly the kind of line
 * that gets added later without thinking.
 */
public record PasswordCandidate(PasswordStrategy strategy, String value, String source) {

    /** Redacted by design — see the class comment. */
    @Override
    public String toString() {
        return "PasswordCandidate[strategy=" + strategy + ", source=" + source + ", value=***]";
    }
}
