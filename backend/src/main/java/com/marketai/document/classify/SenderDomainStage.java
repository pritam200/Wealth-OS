package com.marketai.document.classify;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Stage 1 — resolve the issuer from the envelope sender domain.
 *
 * Establishes <em>who</em> sent the document, not what it is; docType is left to later stages
 * unless the domain itself is single-purpose. Splitting the two is deliberate: a bank sends
 * statements, FD advices and card bills from the same domain, so a domain match that also
 * asserted a type would be wrong most of the time.
 */
@Component
@Order(10)
public class SenderDomainStage implements ClassifierStage {

    @Override
    public ClassificationStage stage() {
        return ClassificationStage.SENDER_DOMAIN;
    }

    @Override
    public Optional<DocumentClassification> classify(ClassificationCandidate candidate) {
        String domain = candidate.senderDomain();
        if (domain == null) return Optional.empty();

        return IssuerDomainRegistry.issuerFor(domain)
            .map(issuer -> DocumentClassification.of(
                DocTypes.UNKNOWN, issuer,
                ClassificationStage.SENDER_DOMAIN,
                "sender domain " + domain));
    }
}
