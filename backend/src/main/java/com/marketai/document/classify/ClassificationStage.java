package com.marketai.document.classify;

/**
 * Stages of the classification cascade, in the order they run. Cheapest and most precise first;
 * the model is a fallback, not a first resort.
 *
 * <p>The ordering is the point. Jupiter — the only Indian fintech to publish its actual Gmail
 * query — narrows on sender and subject alone and stops there. Our long tail needs the later
 * stages, but letting them run first would mean paying a measured ~8 seconds per email to
 * answer a question a domain match settles for free.
 */
public enum ClassificationStage {

    /** Sender domain, preferably DKIM-verified. Highest precision, effectively free. */
    SENDER_DOMAIN(0.95),

    /** Subject-line pattern. */
    SUBJECT(0.85),

    /** Attachment filename, MIME type, or PDF producer metadata. */
    ATTACHMENT(0.80),

    /** First-page text fingerprint — layout markers that survive template changes. */
    FINGERPRINT(0.70),

    /** Small-model classification. Last resort: slow, and its self-reported confidence is
     *  weak evidence (see ConfidenceVector). */
    MODEL(0.0);

    private final double baseConfidence;

    ClassificationStage(double baseConfidence) {
        this.baseConfidence = baseConfidence;
    }

    /**
     * Confidence attributable to matching at this stage, before any stage-specific adjustment.
     * MODEL contributes nothing on its own — a model match must carry its own score, because
     * the stage identity tells you nothing about whether it was right.
     */
    public double baseConfidence() {
        return baseConfidence;
    }

    public boolean isDeterministic() {
        return this != MODEL;
    }
}
