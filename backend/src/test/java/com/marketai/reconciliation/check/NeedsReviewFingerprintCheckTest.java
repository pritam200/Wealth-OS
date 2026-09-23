package com.marketai.reconciliation.check;

import com.marketai.gmail.entity.ImportedTransactionFingerprint;
import com.marketai.gmail.repository.ImportedTransactionFingerprintRepository;
import com.marketai.reconciliation.dto.ReconciliationIssue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@code TransactionMatchScorer} flags a fuzzy (not exact) duplicate candidate as NEEDS_REVIEW
 * and imports it anyway, per the "never silently discard an uncertain transaction" rule. The
 * repository method for reading these back already existed
 * ({@code findByUserIdAndDuplicateStateOrderByImportedAtAsc}, its own Javadoc says "Rows awaiting
 * human review") but had no caller — so a NEEDS_REVIEW row was flagged and then never shown to
 * anyone. This check closes that gap the same way {@code TransactionConflictCheck} already does
 * for conflicting restatements.
 */
class NeedsReviewFingerprintCheckTest {

    @Test
    void surfacesNeedsReviewRowsAsMediumSeverityIssues() {
        ImportedTransactionFingerprintRepository repo = mock(ImportedTransactionFingerprintRepository.class);
        ImportedTransactionFingerprint f = ImportedTransactionFingerprint.builder()
            .id(42L).userId(1L).description("Amazon purchase")
            .amount(new BigDecimal("1200.00")).matchConfidence(0.5).matchedFingerprintId(88L)
            .duplicateState("NEEDS_REVIEW")
            .build();
        when(repo.findByUserIdAndDuplicateStateOrderByImportedAtAsc(1L, "NEEDS_REVIEW"))
            .thenReturn(List.of(f));

        List<ReconciliationIssue> issues = new NeedsReviewFingerprintCheck(repo).run(1L);

        assertThat(issues).hasSize(1);
        ReconciliationIssue issue = issues.get(0);
        assertThat(issue.getSeverity()).isEqualTo("MEDIUM");
        assertThat(issue.getReferenceId()).isEqualTo(42L);
        assertThat(issue.getDescription()).contains("Amazon purchase").contains("50%").contains("88");
    }

    @Test
    void noIssuesWhenNothingNeedsReview() {
        ImportedTransactionFingerprintRepository repo = mock(ImportedTransactionFingerprintRepository.class);
        when(repo.findByUserIdAndDuplicateStateOrderByImportedAtAsc(1L, "NEEDS_REVIEW")).thenReturn(List.of());

        assertThat(new NeedsReviewFingerprintCheck(repo).run(1L)).isEmpty();
    }
}
