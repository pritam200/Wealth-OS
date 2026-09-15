package com.marketai.document.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentStatusTest {

    @Test
    @DisplayName("FAILED is retryable — a transient error must not block a document forever")
    void failedIsNotTerminal() {
        // This is the regression guard for a bug that already shipped here once: a persisted
        // FAILED row made the "already processed" check skip the email on every later sync, so
        // one DB hiccup meant a transaction was never imported and nothing ever said so.
        assertThat(DocumentStatus.FAILED.isTerminal()).isFalse();
        assertThat(DocumentStatus.FAILED.canTransitionTo(DocumentStatus.DISCOVERED)).isTrue();
    }

    @Test
    void onlyImportedAndDuplicateAreTerminal() {
        assertThat(DocumentStatus.IMPORTED.isTerminal()).isTrue();
        assertThat(DocumentStatus.DUPLICATE.isTerminal()).isTrue();

        for (DocumentStatus s : DocumentStatus.values()) {
            if (s != DocumentStatus.IMPORTED && s != DocumentStatus.DUPLICATE) {
                assertThat(s.isTerminal()).as("%s should not be terminal", s).isFalse();
            }
        }
    }

    @Test
    @DisplayName("no path reaches IMPORTED without validation or a human decision")
    void importRequiresValidationOrReview() {
        List<DocumentStatus> canImport = new ArrayList<>();
        for (DocumentStatus s : DocumentStatus.values()) {
            if (s.canTransitionTo(DocumentStatus.IMPORTED)) canImport.add(s);
        }
        // Raw extraction output must never reach the ledger directly.
        assertThat(canImport)
            .containsExactlyInAnyOrder(DocumentStatus.VALIDATED, DocumentStatus.REQUIRES_REVIEW);
        assertThat(DocumentStatus.PARSED.canTransitionTo(DocumentStatus.IMPORTED)).isFalse();
        assertThat(DocumentStatus.DISCOVERED.canTransitionTo(DocumentStatus.IMPORTED)).isFalse();
    }

    @Test
    @DisplayName("an ambiguous document always goes to a human, never straight to import")
    void ambiguityIsNeverAutoResolved() {
        assertThat(DocumentStatus.AMBIGUOUS.allowedNext())
            .containsExactlyInAnyOrder(DocumentStatus.REQUIRES_REVIEW, DocumentStatus.FAILED);
        assertThat(DocumentStatus.AMBIGUOUS.canTransitionTo(DocumentStatus.VALIDATED)).isFalse();
        assertThat(DocumentStatus.AMBIGUOUS.awaitsHuman()).isTrue();
    }

    @Test
    @DisplayName("every state is reachable from DISCOVERED")
    void graphHasNoOrphans() {
        Set<DocumentStatus> seen = EnumSet.of(DocumentStatus.DISCOVERED);
        Deque<DocumentStatus> queue = new ArrayDeque<>(seen);
        while (!queue.isEmpty()) {
            for (DocumentStatus next : queue.pop().allowedNext()) {
                if (seen.add(next)) queue.add(next);
            }
        }
        // An unreachable state is either dead code or a missing transition; both are bugs.
        assertThat(seen).containsExactlyInAnyOrder(DocumentStatus.values());
    }

    @Test
    void terminalStatesAllowNothingFurther() {
        assertThat(DocumentStatus.IMPORTED.allowedNext()).isEmpty();
        assertThat(DocumentStatus.DUPLICATE.allowedNext()).isEmpty();
    }

    @Test
    void nullTransitionsAreRejectedRatherThanThrowing() {
        assertThat(DocumentStatus.DISCOVERED.canTransitionTo(null)).isFalse();
    }

    @Test
    @DisplayName("DUPLICATE is distinct from IMPORTED so 'seen and skipped' is not read as 'never seen'")
    void duplicateBlocksReprocessingButIsNotAnImport() {
        assertThat(DocumentStatus.DUPLICATE.blocksReprocessing()).isTrue();
        assertThat(DocumentStatus.IMPORTED.blocksReprocessing()).isTrue();
        assertThat(DocumentStatus.FAILED.blocksReprocessing()).isFalse();
        assertThat(DocumentStatus.REQUIRES_REVIEW.blocksReprocessing()).isFalse();
    }
}
