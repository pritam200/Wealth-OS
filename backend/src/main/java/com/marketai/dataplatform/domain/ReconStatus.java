package com.marketai.dataplatform.domain;

public enum ReconStatus {
    /** Not yet evaluated, or inside the grace period waiting for an authoritative source. */
    PENDING,
    /** Backed by at least one authoritative source. */
    VERIFIED,
    /** Two or more independent non-authoritative sources agree. */
    MATCHED,
    /** Sources agree on the event but differ on a detail within tolerance. */
    PARTIALLY_MATCHED,
    /** Reported by an authoritative source but absent from the ledger. */
    MISSING,
    DUPLICATE,
    /** Sources disagree on a material field. */
    CONFLICT,
    /** Only non-authoritative evidence exists and the grace period has passed. */
    UNCONFIRMED
}
