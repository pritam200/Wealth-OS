package com.marketai.document.confidence;

import java.util.ArrayList;
import java.util.List;

/**
 * Extraction confidence from several independent signals, kept separate rather than collapsed.
 *
 * <p>Motivated by a specific published result: on the DocILE benchmark, confidence derived from
 * token log-probabilities reaches only ~0.705 ROC AUC, because the dominant error sources —
 * a smudged scan, OCR noise, a misread column boundary — are invisible to the model. A model
 * transcribing OCR noise is confident and wrong at the same time, and no amount of reading its
 * self-reported score will separate those cases.
 *
 * <p>So {@link #modelReported} is recorded and deliberately excluded from the combined score.
 * It is kept for calibration — knowing how badly it correlates with real errors on our own
 * corpus is useful — but it does not get a vote.
 *
 * <p>The components are stored individually so a future threshold change can be replayed
 * against historical documents without re-extracting them. Collapsing to a single number at
 * write time throws away the ability to ask "would the new rule have caught this?".
 *
 * @param arithmetic      do the numbers reconcile against each other (debits + credits = closing
 *                        balance, qty × price + charges = net)
 * @param parserAgreement does the deterministic parser agree with the model
 * @param grounding       fraction of fields whose source span was found in the document
 * @param selfConsistency do repeated extraction passes agree
 * @param modelReported   what the model claimed. Recorded, never trusted.
 */
public record ConfidenceVector(Double arithmetic, Double parserAgreement,
                               Double grounding, Double selfConsistency,
                               Double modelReported, List<String> notes) {

    /** Weights for the signals that do get a vote. They sum to 1.0 across present components. */
    private static final double W_ARITHMETIC  = 0.40;
    private static final double W_GROUNDING   = 0.35;
    private static final double W_AGREEMENT   = 0.20;
    private static final double W_CONSISTENCY = 0.05;

    public static Builder builder() { return new Builder(); }

    /**
     * Weighted mean over the signals actually present, renormalised so a missing signal neither
     * helps nor penalises. Returns 0 when nothing was measured — an unmeasured extraction is
     * not a confident one.
     */
    public double combined() {
        double sum = 0, weight = 0;
        if (arithmetic != null)      { sum += arithmetic * W_ARITHMETIC;       weight += W_ARITHMETIC; }
        if (grounding != null)       { sum += grounding * W_GROUNDING;         weight += W_GROUNDING; }
        if (parserAgreement != null) { sum += parserAgreement * W_AGREEMENT;   weight += W_AGREEMENT; }
        if (selfConsistency != null) { sum += selfConsistency * W_CONSISTENCY; weight += W_CONSISTENCY; }
        return weight == 0 ? 0.0 : sum / weight;
    }

    /**
     * Grounding is a veto, not a weight. If a field's source span is not in the document, the
     * value was not read from it — that is a categorical failure, and no amount of arithmetic
     * agreement elsewhere should be able to average it back up to "confident".
     */
    public boolean hasBlockingFailure() {
        return grounding != null && grounding < 1.0;
    }

    /** Whether this extraction may be imported without a human looking at it. */
    public boolean isAutoAcceptable(double threshold) {
        return !hasBlockingFailure() && combined() >= threshold;
    }

    public static final class Builder {
        private Double arithmetic, parserAgreement, grounding, selfConsistency, modelReported;
        private final List<String> notes = new ArrayList<>();

        public Builder arithmetic(boolean passed, String detail) {
            this.arithmetic = passed ? 1.0 : 0.0;
            if (!passed) notes.add("arithmetic: " + detail);
            return this;
        }

        public Builder grounding(double score, List<String> unfoundFields) {
            this.grounding = score;
            if (!unfoundFields.isEmpty()) {
                notes.add("ungrounded fields: " + String.join(", ", unfoundFields));
            }
            return this;
        }

        public Builder parserAgreement(double score, String detail) {
            this.parserAgreement = score;
            if (score < 1.0 && detail != null) notes.add("parser disagreement: " + detail);
            return this;
        }

        public Builder selfConsistency(double score) {
            this.selfConsistency = score;
            return this;
        }

        /** Recorded for calibration only — see the class comment. */
        public Builder modelReported(Double score) {
            this.modelReported = score;
            return this;
        }

        public ConfidenceVector build() {
            return new ConfidenceVector(arithmetic, parserAgreement, grounding,
                selfConsistency, modelReported, List.copyOf(notes));
        }
    }
}
