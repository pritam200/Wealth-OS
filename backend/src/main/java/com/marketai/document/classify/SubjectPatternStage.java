package com.marketai.document.classify;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Stage 2 — infer the document type from the subject line.
 *
 * Ordered most-specific first, because subjects overlap: "Contract Note cum Tax Invoice" also
 * contains "invoice", and a redemption confirmation also contains "confirmation". First match
 * wins, so the specific phrases must be checked before the generic ones.
 *
 * <p>Subject alone never establishes the issuer — a subject line is not authority about who
 * sent something.
 */
@Component
@Order(20)
public class SubjectPatternStage implements ClassifierStage {

    /** Keyword → docType, most specific first. */
    private static final Map<String, String> PATTERNS = new LinkedHashMap<>();

    static {
        // Highly specific, checked first
        PATTERNS.put("consolidated account statement", DocTypes.CAS_STATEMENT);
        PATTERNS.put("contract note",                  DocTypes.CONTRACT_NOTE);
        PATTERNS.put("trade confirmation",             DocTypes.TRADE_CONFIRM);
        PATTERNS.put("fixed deposit",                  DocTypes.FD_RD_ADVICE);
        PATTERNS.put("recurring deposit",              DocTypes.FD_RD_ADVICE);
        PATTERNS.put("fd booking",                     DocTypes.FD_RD_ADVICE);
        PATTERNS.put("rd booking",                     DocTypes.FD_RD_ADVICE);
        PATTERNS.put("credit card statement",          DocTypes.CARD_STATEMENT);
        PATTERNS.put("card statement",                 DocTypes.CARD_STATEMENT);
        PATTERNS.put("dividend",                       DocTypes.DIVIDEND_ADVICE);
        PATTERNS.put("account statement",              DocTypes.BANK_STATEMENT);

        // Generic MF vocabulary, checked after the specific forms above
        PATTERNS.put("sip",                            DocTypes.MF_TRANSACTION);
        PATTERNS.put("allotment",                      DocTypes.MF_TRANSACTION);
        PATTERNS.put("redemption",                     DocTypes.MF_TRANSACTION);
        PATTERNS.put("switch",                         DocTypes.MF_TRANSACTION);
    }

    @Override
    public ClassificationStage stage() {
        return ClassificationStage.SUBJECT;
    }

    @Override
    public Optional<DocumentClassification> classify(ClassificationCandidate candidate) {
        String subject = candidate.subjectOrEmpty().toLowerCase(Locale.ROOT);
        if (subject.isBlank()) return Optional.empty();

        for (Map.Entry<String, String> e : PATTERNS.entrySet()) {
            if (subject.contains(e.getKey())) {
                return Optional.of(DocumentClassification.of(
                    e.getValue(), null,
                    ClassificationStage.SUBJECT,
                    "subject contains '" + e.getKey() + "'"));
            }
        }
        return Optional.empty();
    }
}
