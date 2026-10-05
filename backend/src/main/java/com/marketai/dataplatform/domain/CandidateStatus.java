package com.marketai.dataplatform.domain;

public enum CandidateStatus {
    PENDING_RECONCILIATION,
    /** Resolved to an existing canonical transaction as another observation of it. */
    MATCHED_EXISTING,
    /** Became a new canonical transaction. */
    PROMOTED,
    REJECTED,
    /** Identical re-delivery of a record already seen from the same source. */
    DUPLICATE
}
