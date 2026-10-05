package com.marketai.dataplatform.domain;

/**
 * Where a financial fact came from, ordered by how far it can be trusted.
 *
 * <p>Financial institutions and authoritative data sources are the source of truth. Email is an
 * ingestion signal. AI inference is never authoritative. A lower {@link #rank} wins when two
 * sources disagree about the same event.
 */
public enum SourceType {
    /** RBI Account Aggregator / the institution's own data. */
    ACCOUNT_AGGREGATOR(1, 1.00),
    BROKER_API(2, 0.98),
    DEPOSITORY(3, 0.97),
    /** Consolidated account statement (CAMS / KFintech / NSDL / CDSL). */
    CAS(3, 0.97),
    /** An official statement: bank, broker or fund-house PDF/CSV/Excel. */
    STATEMENT(4, 0.93),
    /** A transaction notification email. A signal, not a source of truth. */
    EMAIL(5, 0.75),
    MANUAL(6, 0.70),
    AI_INFERENCE(7, 0.40);

    private final int rank;
    private final double baseConfidence;

    SourceType(int rank, double baseConfidence) {
        this.rank = rank;
        this.baseConfidence = baseConfidence;
    }

    public int rank() { return rank; }

    /** Confidence of a structured record from this source before any per-record quality factor. */
    public double baseConfidence() { return baseConfidence; }

    /** Authoritative sources can verify a transaction and override what a weaker source said. */
    public boolean authoritative() { return rank <= STATEMENT.rank; }

    public boolean moreReliableThan(SourceType other) { return rank < other.rank; }
}
