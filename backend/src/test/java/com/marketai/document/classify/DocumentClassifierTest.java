package com.marketai.document.classify;

import com.marketai.common.quality.DataQuality;
import com.marketai.common.quality.Sufficiency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentClassifierTest {

    private final DocumentClassifier classifier =
        new DocumentClassifier(List.of(new SenderDomainStage(), new SubjectPatternStage()));

    private Sufficiency<DocumentClassification> classify(String from, String subject) {
        return classifier.classify(ClassificationCandidate.email(from, subject, ""));
    }

    @Test
    @DisplayName("stages combine — domain gives the issuer, subject gives the type")
    void stagesContributeDifferentFacts() {
        // Neither cheap stage supplies both halves, so taking only the first match would throw
        // away the other half of the answer.
        Sufficiency<DocumentClassification> r =
            classify("noreply@zerodha.com", "Contract Note cum Tax Invoice");

        assertThat(r.isUsable()).isTrue();
        assertThat(r.quality()).isEqualTo(DataQuality.FULL);

        DocumentClassification c = r.asOptional().orElseThrow();
        assertThat(c.issuer()).isEqualTo("Zerodha");
        assertThat(c.docType()).isEqualTo(DocTypes.CONTRACT_NOTE);
        assertThat(c.matchedStage()).isEqualTo(ClassificationStage.SENDER_DOMAIN);
        assertThat(c.evidence()).contains("zerodha.com").contains("contract note");
    }

    @Test
    @DisplayName("issuer without type is usable but flagged PARTIAL")
    void halfAnswersAreDowngraded() {
        Sufficiency<DocumentClassification> r =
            classify("noreply@zerodha.com", "Your monthly newsletter");

        assertThat(r.quality()).isEqualTo(DataQuality.PARTIAL);
        assertThat(r.reason()).contains("document type unknown");
        assertThat(r.asOptional().orElseThrow().issuer()).isEqualTo("Zerodha");
    }

    @Test
    void typeWithoutIssuerIsAlsoPartial() {
        Sufficiency<DocumentClassification> r =
            classify("statements@unknownbroker.example", "Contract Note");

        assertThat(r.quality()).isEqualTo(DataQuality.PARTIAL);
        assertThat(r.reason()).contains("issuer unknown");
        assertThat(r.asOptional().orElseThrow().docType()).isEqualTo(DocTypes.CONTRACT_NOTE);
    }

    @Test
    @DisplayName("recognising nothing declines, and says what would fix it")
    void unrecognisedDocumentsDecline() {
        Sufficiency<DocumentClassification> r = classify("friend@example.com", "lunch tomorrow?");

        assertThat(r.isUsable()).isFalse();
        assertThat(r.value()).isNull();
        assertThat(r.whatWouldFixIt())
            .contains("IssuerDomainRegistry")
            .contains("SubjectPatternStage");
    }

    @Test
    @DisplayName("specific subject patterns win over the generic ones they contain")
    void patternOrderingIsSpecificFirst() {
        // "Consolidated Account Statement" also contains "account statement"; the CAS reading
        // is the correct one and must not be shadowed by the generic bank-statement pattern.
        assertThat(classify("x@camsonline.com", "Consolidated Account Statement - CAMS")
            .asOptional().orElseThrow().docType()).isEqualTo(DocTypes.CAS_STATEMENT);

        assertThat(classify("x@hdfcbank.com", "Your Account Statement")
            .asOptional().orElseThrow().docType()).isEqualTo(DocTypes.BANK_STATEMENT);
    }

    @Test
    @DisplayName("a stage that throws does not sink the cascade")
    void misbehavingStagesAreSkipped() {
        ClassifierStage exploding = new ClassifierStage() {
            @Override public ClassificationStage stage() { return ClassificationStage.FINGERPRINT; }
            @Override public Optional<DocumentClassification> classify(ClassificationCandidate c) {
                throw new IllegalStateException("boom");
            }
        };

        DocumentClassifier withBadStage = new DocumentClassifier(
            List.of(exploding, new SenderDomainStage(), new SubjectPatternStage()));

        Sufficiency<DocumentClassification> r = withBadStage.classify(
            ClassificationCandidate.email("noreply@zerodha.com", "Contract Note", ""));

        // Stranding a document because one stage has a bug is worse than classifying it.
        assertThat(r.isUsable()).isTrue();
        assertThat(r.asOptional().orElseThrow().issuer()).isEqualTo("Zerodha");
    }

    @Test
    void nullCandidateDeclinesRatherThanThrowing() {
        assertThat(classifier.classify(null).isUsable()).isFalse();
    }

    @Test
    @DisplayName("a spoofed display name cannot classify as the impersonated issuer")
    void spoofedSenderDoesNotReachAnIssuer() {
        Sufficiency<DocumentClassification> r =
            classify("\"Zerodha\" <attacker@evil.example>", "Contract Note");

        DocumentClassification c = r.asOptional().orElseThrow();
        assertThat(c.issuer()).isNull();
        assertThat(r.quality()).isEqualTo(DataQuality.PARTIAL);
    }
}
