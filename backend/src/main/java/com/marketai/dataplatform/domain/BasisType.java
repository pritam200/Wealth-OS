package com.marketai.dataplatform.domain;

/** How a holding snapshot was produced. */
public enum BasisType {
    /** Replayed from the canonical ledger. */
    LEDGER_CALCULATED,
    /** Reported by the institution — the verification source. */
    INSTITUTION_REPORTED
}
