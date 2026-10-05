package com.marketai.dataplatform.domain;

public enum TxnStatus {
    /** Observed from a non-authoritative source; not yet corroborated. */
    PENDING_RECONCILIATION,
    CONFIRMED,
    /** A person or an authoritative source ruled it out. Kept for audit, ignored by calculations. */
    REJECTED,
    /** Cancelled or reversed by the institution. Kept for audit, ignored by calculations. */
    REVERSED;

    /** Whether calculations (holdings, cost basis) count it. */
    public boolean counts() { return this == PENDING_RECONCILIATION || this == CONFIRMED; }
}
