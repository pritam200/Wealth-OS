package com.marketai.document.route;

/**
 * Result of running legacy parser selection and classifier-based routing over the same email.
 *
 * <p>Exists so the routing change can be evaluated against real synced mail before it decides
 * anything. Switching selection on the strength of unit tests alone would be a guess about the
 * shape of a corpus we can actually measure.
 *
 * @param legacyParser  what the substring scan chose, or null
 * @param routedParser  what routing chose, or null
 * @param verdict       how the two compare
 * @param detail        human-readable explanation, safe to log (no message content)
 */
public record SelectionComparison(String legacyParser, String routedParser,
                                  Verdict verdict, String detail) {

    public enum Verdict {
        /** Both picked the same parser. The expected outcome for the overwhelming majority. */
        AGREE,

        /** Neither picked anything. Also agreement — the email is not parseable either way. */
        AGREE_NEITHER,

        /**
         * Routing found a parser the legacy scan missed. An improvement, but still a behaviour
         * change worth seeing before it takes effect.
         */
        ROUTED_ONLY,

        /**
         * The legacy scan found a parser routing would not reach. <b>The dangerous case</b> —
         * switching over would stop importing something that imports today.
         */
        LEGACY_ONLY,

        /** Both picked, but picked differently. Needs a human to say which is right. */
        DISAGREE
    }

    public boolean isRegression() {
        return verdict == Verdict.LEGACY_ONLY || verdict == Verdict.DISAGREE;
    }

    public boolean isClean() {
        return verdict == Verdict.AGREE || verdict == Verdict.AGREE_NEITHER;
    }

    static SelectionComparison of(String legacy, String routed) {
        if (legacy == null && routed == null) {
            return new SelectionComparison(null, null, Verdict.AGREE_NEITHER,
                "Neither selection path matched a parser");
        }
        if (legacy == null) {
            return new SelectionComparison(null, routed, Verdict.ROUTED_ONLY,
                "Routing selected " + routed + " where the legacy scan matched nothing");
        }
        if (routed == null) {
            return new SelectionComparison(legacy, null, Verdict.LEGACY_ONLY,
                "Legacy scan selected " + legacy + " but routing matched nothing — switching "
                    + "would stop importing this");
        }
        if (legacy.equals(routed)) {
            return new SelectionComparison(legacy, routed, Verdict.AGREE,
                "Both selected " + legacy);
        }
        return new SelectionComparison(legacy, routed, Verdict.DISAGREE,
            "Legacy selected " + legacy + ", routing selected " + routed);
    }
}
