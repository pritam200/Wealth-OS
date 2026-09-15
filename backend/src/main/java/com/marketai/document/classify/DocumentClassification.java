package com.marketai.document.classify;

import com.marketai.common.quality.Sufficiency;

/**
 * What a document is and who issued it, plus how confidently and by which route we know that.
 *
 * {@code matchedStage} is retained rather than discarded because it is operationally useful:
 * a population suddenly dominated by {@link ClassificationStage#MODEL} means the cheap
 * deterministic stages have stopped matching — an issuer changed their sender domain or subject
 * template — and that is worth knowing before accuracy quietly degrades.
 */
public record DocumentClassification(String docType, String issuer,
                                     double confidence, ClassificationStage matchedStage,
                                     String evidence) {

    public static DocumentClassification of(String docType, String issuer,
                                            ClassificationStage stage, String evidence) {
        return new DocumentClassification(docType, issuer, stage.baseConfidence(), stage, evidence);
    }

    public static DocumentClassification of(String docType, String issuer, double confidence,
                                            ClassificationStage stage, String evidence) {
        return new DocumentClassification(docType, issuer, confidence, stage, evidence);
    }

    /** Wraps this as a usable result. */
    public Sufficiency<DocumentClassification> asSufficiency() {
        return Sufficiency.full(this);
    }
}
