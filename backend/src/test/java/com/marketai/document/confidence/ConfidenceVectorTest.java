package com.marketai.document.confidence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfidenceVectorTest {

    @Test
    void allSignalsPassingYieldsFullConfidence() {
        ConfidenceVector v = ConfidenceVector.builder()
            .arithmetic(true, null)
            .grounding(1.0, List.of())
            .parserAgreement(1.0, null)
            .selfConsistency(1.0)
            .build();

        assertThat(v.combined()).isEqualTo(1.0);
        assertThat(v.isAutoAcceptable(0.85)).isTrue();
        assertThat(v.notes()).isEmpty();
    }

    @Test
    @DisplayName("an ungrounded field vetoes auto-accept regardless of the weighted score")
    void groundingIsAVetoNotAWeight() {
        ConfidenceVector v = ConfidenceVector.builder()
            .arithmetic(true, null)
            .grounding(0.9, List.of("stt"))     // one field of ten was not in the document
            .parserAgreement(1.0, null)
            .selfConsistency(1.0)
            .build();

        // The weighted mean is still high — which is exactly why grounding cannot be just
        // another weight. A value not present in the source was not read from it.
        assertThat(v.combined()).isGreaterThan(0.9);
        assertThat(v.hasBlockingFailure()).isTrue();
        assertThat(v.isAutoAcceptable(0.85)).isFalse();
        assertThat(v.notes()).anyMatch(n -> n.contains("ungrounded fields: stt"));
    }

    @Test
    @DisplayName("the model's own confidence is recorded but gets no vote")
    void modelReportedDoesNotInfluenceTheScore() {
        // Published result: logprob-derived confidence reaches only ~0.705 ROC AUC on DocILE,
        // because OCR noise produces high-probability wrong answers. Recording it is useful for
        // calibration; trusting it is not.
        ConfidenceVector confident = ConfidenceVector.builder()
            .arithmetic(false, "qty x price does not reconcile")
            .grounding(1.0, List.of())
            .modelReported(0.99)
            .build();

        ConfidenceVector modest = ConfidenceVector.builder()
            .arithmetic(false, "qty x price does not reconcile")
            .grounding(1.0, List.of())
            .modelReported(0.10)
            .build();

        assertThat(confident.combined()).isEqualTo(modest.combined());
        assertThat(confident.modelReported()).isEqualTo(0.99);
        assertThat(confident.isAutoAcceptable(0.85)).isFalse();
    }

    @Test
    @DisplayName("failing arithmetic drops confidence below any sane threshold")
    void arithmeticFailureDominates() {
        ConfidenceVector v = ConfidenceVector.builder()
            .arithmetic(false, "qty 25 x price 1472.50 = 36832.50 but net amount reads 35332.50")
            .grounding(1.0, List.of())
            .parserAgreement(1.0, null)
            .build();

        assertThat(v.combined()).isLessThan(0.85);
        assertThat(v.isAutoAcceptable(0.85)).isFalse();
        assertThat(v.notes()).anyMatch(n -> n.contains("arithmetic:"));
    }

    @Test
    @DisplayName("measuring nothing scores zero, not one")
    void unmeasuredExtractionIsNotConfident() {
        ConfidenceVector empty = ConfidenceVector.builder().build();

        assertThat(empty.combined()).isZero();
        assertThat(empty.isAutoAcceptable(0.0)).isTrue();  // only at a zero threshold
        assertThat(empty.isAutoAcceptable(0.01)).isFalse();
    }

    @Test
    void missingSignalsAreRenormalisedRatherThanPenalised() {
        // Self-consistency is expensive (repeated passes) and often skipped. Its absence should
        // not make an otherwise-clean extraction look worse than one that ran it.
        ConfidenceVector withoutConsistency = ConfidenceVector.builder()
            .arithmetic(true, null)
            .grounding(1.0, List.of())
            .parserAgreement(1.0, null)
            .build();

        assertThat(withoutConsistency.combined()).isEqualTo(1.0);
    }

    @Test
    void parserDisagreementIsRecordedWithItsDetail() {
        ConfidenceVector v = ConfidenceVector.builder()
            .arithmetic(true, null)
            .grounding(1.0, List.of())
            .parserAgreement(0.5, "ZerodhaParser read qty=25, model read qty=250")
            .build();

        assertThat(v.combined()).isLessThan(1.0);
        assertThat(v.notes()).anyMatch(n -> n.contains("qty=250"));
    }
}
