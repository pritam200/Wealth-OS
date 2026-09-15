package com.marketai.document.classify;

import java.util.Optional;

/**
 * One rung of the cascade. A stage either recognises the document or abstains — it never
 * guesses, because a wrong confident answer here routes the document to the wrong extractor
 * and every value downstream inherits that mistake.
 */
public interface ClassifierStage {

    ClassificationStage stage();

    /** Empty means "not mine" — the cascade moves to the next stage. */
    Optional<DocumentClassification> classify(ClassificationCandidate candidate);
}
