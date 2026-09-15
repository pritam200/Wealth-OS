package com.marketai.identity.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * A documented way an Indian financial institution derives a statement password from the
 * holder's own identifiers.
 *
 * <p>Every value here corresponds to a format an issuer actually publishes — not a guess, and
 * not a permutation generated to widen the search. The spec is explicit that passwords must
 * never be brute-forced: only formats supported by a provider rule, an instruction in the email
 * body, or a credential the user explicitly saved may be attempted. Enumerating strategies as a
 * closed set is what makes that enforceable — there is no code path that can invent a candidate
 * outside this enum.
 */
public enum PasswordStrategy {

    /** PAN in uppercase. The most common convention: CAMS, KFintech, NSE, m.Stock. */
    PAN_UPPERCASE,

    /** PAN in lowercase. Rare, but some portals normalise downward. */
    PAN_LOWERCASE,

    /** Date of birth as DDMMYYYY. Common for bank statements. */
    DOB_DDMMYYYY,

    /** Date of birth as DDMMYY. */
    DOB_DDMMYY,

    /** PAN (uppercase) immediately followed by DOB as DDMMYYYY. */
    PAN_UPPERCASE_PLUS_DOB_DDMMYYYY,

    /** First four PAN characters (uppercase) followed by DOB as DDMMYYYY — CDSL/NSDL eCAS. */
    PAN_FIRST4_PLUS_DOB_DDMMYYYY,

    /** First five PAN characters (uppercase) followed by DOB as DDMMYYYY. */
    PAN_FIRST5_PLUS_DOB_DDMMYYYY,

    /** A password the user typed in and chose to save for this provider. */
    CUSTOM_SAVED;

    private static final DateTimeFormatter DDMMYYYY = DateTimeFormatter.ofPattern("ddMMyyyy");
    private static final DateTimeFormatter DDMMYY = DateTimeFormatter.ofPattern("ddMMyy");

    /**
     * Derives the candidate password, or null when the inputs this strategy needs are absent.
     *
     * <p>Returning null rather than a partial string is deliberate: a strategy that cannot be
     * satisfied must drop out of the candidate list entirely, not contribute a malformed
     * attempt that burns one of the bounded retries.
     *
     * @param pan uppercase PAN, or null if the vault has none
     * @param dob date of birth, or null if the vault has none
     */
    public String derive(String pan, LocalDate dob) {
        return switch (this) {
            case PAN_UPPERCASE -> pan == null ? null : pan.toUpperCase();
            case PAN_LOWERCASE -> pan == null ? null : pan.toLowerCase();
            case DOB_DDMMYYYY -> dob == null ? null : dob.format(DDMMYYYY);
            case DOB_DDMMYY -> dob == null ? null : dob.format(DDMMYY);
            case PAN_UPPERCASE_PLUS_DOB_DDMMYYYY ->
                (pan == null || dob == null) ? null : pan.toUpperCase() + dob.format(DDMMYYYY);
            case PAN_FIRST4_PLUS_DOB_DDMMYYYY ->
                (pan == null || dob == null || pan.length() < 4) ? null
                    : pan.toUpperCase().substring(0, 4) + dob.format(DDMMYYYY);
            case PAN_FIRST5_PLUS_DOB_DDMMYYYY ->
                (pan == null || dob == null || pan.length() < 5) ? null
                    : pan.toUpperCase().substring(0, 5) + dob.format(DDMMYYYY);
            // Not derivable from identity — supplied from the saved-credential store instead.
            case CUSTOM_SAVED -> null;
        };
    }

    /** Human-readable description for the UI. Describes the *format*, never the value. */
    public String describe() {
        return switch (this) {
            case PAN_UPPERCASE -> "PAN (uppercase)";
            case PAN_LOWERCASE -> "PAN (lowercase)";
            case DOB_DDMMYYYY -> "Date of birth (DDMMYYYY)";
            case DOB_DDMMYY -> "Date of birth (DDMMYY)";
            case PAN_UPPERCASE_PLUS_DOB_DDMMYYYY -> "PAN + date of birth (DDMMYYYY)";
            case PAN_FIRST4_PLUS_DOB_DDMMYYYY -> "First 4 of PAN + date of birth (DDMMYYYY)";
            case PAN_FIRST5_PLUS_DOB_DDMMYYYY -> "First 5 of PAN + date of birth (DDMMYYYY)";
            case CUSTOM_SAVED -> "Saved credential";
        };
    }

    public boolean needsPan() {
        return this == PAN_UPPERCASE || this == PAN_LOWERCASE
            || this == PAN_UPPERCASE_PLUS_DOB_DDMMYYYY
            || this == PAN_FIRST4_PLUS_DOB_DDMMYYYY
            || this == PAN_FIRST5_PLUS_DOB_DDMMYYYY;
    }

    public boolean needsDob() {
        return this == DOB_DDMMYYYY || this == DOB_DDMMYY
            || this == PAN_UPPERCASE_PLUS_DOB_DDMMYYYY
            || this == PAN_FIRST4_PLUS_DOB_DDMMYYYY
            || this == PAN_FIRST5_PLUS_DOB_DDMMYYYY;
    }
}
