package com.marketai.document.classify;

/**
 * How much the sender of a document can be believed.
 *
 * <p>Separate from classification confidence, which answers "what is this document". This
 * answers "is whoever sent it who they claim to be" — and the two can disagree sharply. A
 * perfectly-formed contract note from an attacker-controlled address is high-confidence and
 * untrustworthy at the same time.
 */
public enum SenderTrust {

    /** The envelope domain is a known issuer domain, and nothing in the header contradicts it. */
    VERIFIED_DOMAIN,

    /**
     * Well-formed sender, but the domain is not in the registry. The common case for a
     * legitimate issuer we have not catalogued yet — so this must not block an import, or
     * adding a bank would mean losing transactions until someone noticed.
     */
    UNKNOWN_DOMAIN,

    /**
     * The display name claims one issuer while the envelope domain says another, or says
     * nothing verifiable. This is the impersonation shape, and it is the case worth acting on:
     * a display name is set by whoever sent the message.
     */
    IMPERSONATION_SUSPECTED,

    /** No usable sender at all. */
    UNPARSEABLE;

    /**
     * Whether a document from this sender may be imported without a human looking at it.
     *
     * <p>{@link #UNKNOWN_DOMAIN} deliberately passes. Blocking it would break every legitimate
     * issuer missing from the registry, and the downstream fingerprint check and review queue
     * already constrain what an unrecognised sender can do. Only the impersonation shape is
     * held back, because that is the only case where we have positive evidence of a lie.
     */
    public boolean permitsAutoImport() {
        return this != IMPERSONATION_SUSPECTED;
    }
}
