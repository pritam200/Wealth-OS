package com.marketai.document.identity;

/**
 * A payment-rail reference carried by the document itself.
 *
 * <p>These are the highest-value fields on the page. A reference issued by the rail is stable
 * across every notification about the same payment, so two documents quoting the same UTR are
 * the same economic event — no tolerance windows, no fuzzy scoring, no guessing. Harvesting
 * them aggressively converts most of the matching problem from statistics into a lookup.
 *
 * @param type  which rail issued it
 * @param value normalised — upper-cased, separators stripped
 * @param label the literal text that introduced it in the document, kept for traceability
 */
public record ExternalReference(ReferenceType type, String value, String label) {

    public enum ReferenceType {
        /** NEFT/RTGS Unique Transaction Reference. Bank-issued, globally unique. */
        UTR,
        /** Retrieval Reference Number — card and UPI rails. 12 digits. */
        RRN,
        /** UPI transaction id. */
        UPI_TXN_ID,
        /** IMPS reference. */
        IMPS_REF,
        /** Cheque number. Unique only within an account, so weaker than the others. */
        CHEQUE,
        /** An issuer's own transaction/order reference. Unique within that issuer only. */
        ISSUER_REF;

        /**
         * Whether a match on this reference alone is sufficient to declare two events identical.
         *
         * <p>{@link #CHEQUE} and {@link #ISSUER_REF} are not: a cheque number repeats across
         * accounts, and two brokers can independently mint order id "12345". Matching on those
         * without also agreeing on the account or issuer would merge unrelated transactions,
         * which is worse than failing to merge related ones.
         */
        public boolean isGloballyUnique() {
            return this == UTR || this == RRN || this == UPI_TXN_ID || this == IMPS_REF;
        }
    }

    public boolean isGloballyUnique() {
        return type.isGloballyUnique();
    }
}
