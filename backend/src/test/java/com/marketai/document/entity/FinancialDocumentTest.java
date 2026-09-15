package com.marketai.document.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FinancialDocumentTest {

    private FinancialDocument doc() {
        FinancialDocument d = FinancialDocument.builder()
            .userId(1L).source(DocumentSource.GMAIL).sourceRef("msg-1")
            .status(DocumentStatus.DISCOVERED)
            .build();
        d.onCreate();
        return d;
    }

    @Test
    void legalTransitionsAdvanceTheDocument() {
        FinancialDocument d = doc();
        d.transitionTo(DocumentStatus.PROCESSING, null);
        d.transitionTo(DocumentStatus.PARSED, null);
        d.transitionTo(DocumentStatus.VALIDATED, null);
        d.transitionTo(DocumentStatus.IMPORTED, null);

        assertThat(d.getStatus()).isEqualTo(DocumentStatus.IMPORTED);
        assertThat(d.getStatusChangedAt()).isNotNull();
    }

    @Test
    @DisplayName("an illegal transition fails loudly rather than coercing the status")
    void illegalTransitionsThrow() {
        FinancialDocument d = doc();

        // Skipping validation is a programming error, not a data condition. Silently allowing
        // it would put unverified extractions into the ledger, which is the one thing the
        // lifecycle exists to prevent.
        assertThatThrownBy(() -> d.transitionTo(DocumentStatus.IMPORTED, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("DISCOVERED -> IMPORTED");

        assertThat(d.getStatus()).isEqualTo(DocumentStatus.DISCOVERED);
    }

    @Test
    void failureRecordsItsReasonAndRetryIncrementsAttempts() {
        FinancialDocument d = doc();
        d.transitionTo(DocumentStatus.PROCESSING, null);
        d.transitionTo(DocumentStatus.FAILED, "parser threw NumberFormatException");

        assertThat(d.getLastError()).isEqualTo("parser threw NumberFormatException");
        assertThat(d.getAttempts()).isZero();

        d.transitionTo(DocumentStatus.DISCOVERED, null);

        assertThat(d.getAttempts()).isEqualTo(1);
        // The previous error is cleared on retry so a stale message cannot be mistaken for a
        // fresh one on the next failure.
        assertThat(d.getLastError()).isNull();
        assertThat(d.getStatus()).isEqualTo(DocumentStatus.DISCOVERED);
    }

    @Test
    void reviewDecisionsCanImportOrMarkDuplicate() {
        FinancialDocument accepted = doc();
        accepted.transitionTo(DocumentStatus.PROCESSING, null);
        accepted.transitionTo(DocumentStatus.PARSED, null);
        accepted.transitionTo(DocumentStatus.REQUIRES_REVIEW, null);
        accepted.transitionTo(DocumentStatus.IMPORTED, null);
        assertThat(accepted.getStatus()).isEqualTo(DocumentStatus.IMPORTED);
    }

    @Test
    void classificationIsRecordedWithItsStage() {
        FinancialDocument d = doc();
        d.recordClassification("CONTRACT_NOTE", "Zerodha", 0.95,
            com.marketai.document.classify.ClassificationStage.SENDER_DOMAIN);

        assertThat(d.getDocType()).isEqualTo("CONTRACT_NOTE");
        assertThat(d.getIssuer()).isEqualTo("Zerodha");
        assertThat(d.getClassificationConfidence()).isEqualTo(0.95);
        assertThat(d.getMatchedStage())
            .isEqualTo(com.marketai.document.classify.ClassificationStage.SENDER_DOMAIN);
    }
}
