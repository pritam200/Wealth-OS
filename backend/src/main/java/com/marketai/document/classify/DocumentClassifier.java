package com.marketai.document.classify;

import com.marketai.common.quality.Sufficiency;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Runs the classification cascade and merges what the stages found.
 *
 * <p>Stages contribute different facts, so they <em>combine</em> rather than compete: the sender
 * domain establishes the issuer, the subject establishes the type. Taking only the first stage
 * that matched anything would throw away the other half of the answer — a Zerodha contract note
 * needs both "Zerodha" and "CONTRACT_NOTE", and no single cheap stage supplies both.
 *
 * <p>This replaces an O(n) scan across 18 parsers each answering a private
 * {@code canParse(from, subject)}, where the first parser to say yes won, nothing recorded how
 * confident that was, and adding a parser could silently shadow an existing one.
 *
 * <p>The classifier does not choose an extractor or touch a ledger. It answers
 * "what is this and who sent it", with a confidence and an audit trail of why.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentClassifier {

    /** Injected in {@code @Order} sequence — cheapest and most precise first. */
    private final List<ClassifierStage> stages;

    /**
     * Below this, the answer is not actionable on its own and the document should be reviewed
     * rather than routed. Deliberately a named constant: the previous flat 0.85 was chosen by
     * feel, and the research is explicit that thresholds must be calibrated against a real
     * sample rather than picked.
     */
    public static final double ACTIONABLE_CONFIDENCE = 0.70;

    public Sufficiency<DocumentClassification> classify(ClassificationCandidate candidate) {
        if (candidate == null) {
            return Sufficiency.insufficient(
                "No candidate supplied",
                "Pass the message headers and body to the classifier");
        }

        String issuer = null;
        String docType = null;
        ClassificationStage earliest = null;
        double confidence = 0.0;
        StringBuilder evidence = new StringBuilder();

        for (ClassifierStage s : stages) {
            Optional<DocumentClassification> result;
            try {
                result = s.classify(candidate);
            } catch (RuntimeException e) {
                // One misbehaving stage must not sink the cascade — the later stages may still
                // classify this correctly, and a hard failure here would strand the document.
                log.warn("Classifier stage {} threw: {}", s.stage(), e.toString());
                continue;
            }
            if (result.isEmpty()) continue;

            DocumentClassification c = result.get();
            boolean contributed = false;

            if (issuer == null && c.issuer() != null) {
                issuer = c.issuer();
                contributed = true;
            }
            if (docType == null && c.docType() != null && !DocTypes.UNKNOWN.equals(c.docType())) {
                docType = c.docType();
                contributed = true;
            }
            if (!contributed) continue;

            if (earliest == null) earliest = c.matchedStage();
            confidence = Math.max(confidence, c.confidence());
            if (!evidence.isEmpty()) evidence.append("; ");
            evidence.append(c.evidence());

            if (issuer != null && docType != null) break;   // nothing left to learn
        }

        if (issuer == null && docType == null) {
            return Sufficiency.insufficient(
                "No classifier stage recognised this document",
                "Add the sender domain to IssuerDomainRegistry, or a subject pattern to "
                    + "SubjectPatternStage, if this issuer should be supported");
        }

        // A half-answer is usable but must say so. Routing on issuer alone, or type alone, is
        // weaker evidence than both agreeing, and the caller needs to know which it got.
        boolean complete = issuer != null && docType != null;
        double finalConfidence = complete ? confidence : confidence * 0.8;

        DocumentClassification classification = DocumentClassification.of(
            docType != null ? docType : DocTypes.UNKNOWN,
            issuer,
            finalConfidence,
            earliest,
            evidence.toString());

        if (complete && finalConfidence >= ACTIONABLE_CONFIDENCE) {
            return Sufficiency.full(classification);
        }
        return Sufficiency.partial(classification, complete
            ? "Classified, but below the actionable confidence threshold"
            : (issuer == null ? "Document type recognised but issuer unknown"
                              : "Issuer recognised but document type unknown"));
    }
}
