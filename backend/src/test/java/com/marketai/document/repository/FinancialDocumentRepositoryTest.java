package com.marketai.document.repository;

import com.marketai.document.entity.DocumentSource;
import com.marketai.document.entity.DocumentStatus;
import com.marketai.document.entity.FinancialDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the JPA mapping and the JPQL. Both are only checked when a real persistence context
 * starts — a typo in a fully-qualified enum inside a @Query compiles fine and fails at boot.
 */
@DataJpaTest
class FinancialDocumentRepositoryTest {

    @Autowired
    private FinancialDocumentRepository repo;

    private FinancialDocument saved(Long userId, String ref, DocumentStatus status, int attempts) {
        FinancialDocument d = FinancialDocument.builder()
            .userId(userId).source(DocumentSource.GMAIL).sourceRef(ref)
            .contentHash("hash-" + ref).status(status).attempts(attempts)
            .firstSeenAt(LocalDateTime.now().minusHours(2))
            .statusChangedAt(LocalDateTime.now().minusHours(2))
            .build();
        return repo.saveAndFlush(d);
    }

    @Test
    @DisplayName("the entity round-trips, including the enum columns")
    void entityPersists() {
        FinancialDocument d = saved(1L, "msg-1", DocumentStatus.DISCOVERED, 0);

        FinancialDocument found = repo.findById(d.getId()).orElseThrow();
        assertThat(found.getSource()).isEqualTo(DocumentSource.GMAIL);
        assertThat(found.getStatus()).isEqualTo(DocumentStatus.DISCOVERED);
        assertThat(found.getSourceRef()).isEqualTo("msg-1");
    }

    @Test
    void lookupBySourceRefIsScopedToTheUser() {
        saved(1L, "msg-shared", DocumentStatus.IMPORTED, 0);
        saved(2L, "msg-shared", DocumentStatus.DISCOVERED, 0);

        assertThat(repo.findByUserIdAndSourceAndSourceRef(1L, DocumentSource.GMAIL, "msg-shared"))
            .get().extracting(FinancialDocument::getStatus).isEqualTo(DocumentStatus.IMPORTED);
        assertThat(repo.findByUserIdAndSourceAndSourceRef(2L, DocumentSource.GMAIL, "msg-shared"))
            .get().extracting(FinancialDocument::getStatus).isEqualTo(DocumentStatus.DISCOVERED);
    }

    @Test
    @DisplayName("findRetryable returns failed documents under the attempt cap")
    void retryableRespectsTheAttemptCap() {
        saved(1L, "fail-1", DocumentStatus.FAILED, 0);
        saved(1L, "fail-2", DocumentStatus.FAILED, 2);
        saved(1L, "fail-exhausted", DocumentStatus.FAILED, 3);
        saved(1L, "ok", DocumentStatus.IMPORTED, 0);

        List<FinancialDocument> retryable = repo.findRetryable(3);

        assertThat(retryable).extracting(FinancialDocument::getSourceRef)
            .containsExactlyInAnyOrder("fail-1", "fail-2");
    }

    @Test
    @DisplayName("findStalled surfaces documents a dead worker left mid-flight")
    void stalledDocumentsAreFound() {
        saved(1L, "stuck", DocumentStatus.PROCESSING, 0);
        saved(1L, "parsed-stuck", DocumentStatus.PARSED, 0);
        saved(1L, "done", DocumentStatus.IMPORTED, 0);

        List<FinancialDocument> stalled = repo.findStalled(LocalDateTime.now().minusHours(1));

        assertThat(stalled).extracting(FinancialDocument::getSourceRef)
            .containsExactlyInAnyOrder("stuck", "parsed-stuck");
    }

    @Test
    void statusCountsAggregateForDataHealth() {
        saved(1L, "a", DocumentStatus.IMPORTED, 0);
        saved(1L, "b", DocumentStatus.IMPORTED, 0);
        saved(1L, "c", DocumentStatus.REQUIRES_REVIEW, 0);
        saved(2L, "other-user", DocumentStatus.IMPORTED, 0);

        assertThat(repo.countByUserIdAndStatus(1L, DocumentStatus.IMPORTED)).isEqualTo(2);
        assertThat(repo.countByStatusForUser(1L)).hasSize(2);
    }
}
