package com.marketai.reconciliation.check;

import com.marketai.ai.review.entity.EmailReviewItem;
import com.marketai.ai.review.entity.ReviewStatus;
import com.marketai.ai.review.repository.EmailReviewItemRepository;
import com.marketai.document.entity.DocumentStatus;
import com.marketai.document.entity.DocumentSource;
import com.marketai.document.entity.FinancialDocument;
import com.marketai.document.repository.FinancialDocumentRepository;
import com.marketai.ledger.entity.CashAccount;
import com.marketai.ledger.entity.LedgerTransfer;
import com.marketai.ledger.repository.CashAccountRepository;
import com.marketai.ledger.repository.LedgerTransferRepository;
import com.marketai.reconciliation.dto.ReconciliationIssue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class ReconciliationRegistryTest {

    private static final Long USER = 3L;

    @Test
    @DisplayName("an unapplied transfer is caught — net worth is overstated by its amount")
    void unappliedTransferIsCaught() {
        LedgerTransferRepository repo = mock(LedgerTransferRepository.class);
        LedgerTransfer stuck = LedgerTransfer.builder()
            .id(11L).amount(new BigDecimal("50000.00"))
            .transferDate(LocalDate.of(2026, 9, 1)).applied(false).build();
        when(repo.findByUser_IdOrderByTransferDateDesc(USER)).thenReturn(List.of(stuck));

        List<ReconciliationIssue> issues = new UnappliedTransferCheck(repo).run(USER);

        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst().getSeverity()).isEqualTo("HIGH");
        assertThat(issues.getFirst().getDescription()).contains("50000.00").contains("overstated");
    }

    @Test
    void appliedTransfersAreSilent() {
        LedgerTransferRepository repo = mock(LedgerTransferRepository.class);
        when(repo.findByUser_IdOrderByTransferDateDesc(USER)).thenReturn(List.of(
            LedgerTransfer.builder().id(1L).amount(new BigDecimal("100"))
                .transferDate(LocalDate.now()).applied(true).build()));

        assertThat(new UnappliedTransferCheck(repo).run(USER)).isEmpty();
    }

    @Test
    @DisplayName("a negative cash balance is reported, not clamped")
    void negativeCashIsReported() {
        CashAccountRepository repo = mock(CashAccountRepository.class);
        CashAccount acc = new CashAccount();
        acc.setId(4L); acc.setName("HDFC Savings");
        acc.setBalance(new BigDecimal("-2500.00"));
        when(repo.findByUser_Id(USER)).thenReturn(List.of(acc));

        List<ReconciliationIssue> issues = new NegativeCashCheck(repo).run(USER);

        // Clamping to zero would make the number look plausible while leaving the missing
        // inflow undiscovered and net worth silently wrong.
        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst().getDescription())
            .contains("HDFC Savings").contains("-2500.00");
    }

    @Test
    @DisplayName("stalled and failed documents are both surfaced as invisible losses")
    void stalledAndFailedDocumentsAreSurfaced() {
        FinancialDocumentRepository repo = mock(FinancialDocumentRepository.class);

        FinancialDocument stalled = FinancialDocument.builder()
            .id(1L).userId(USER).source(DocumentSource.GMAIL).sourceRef("msg-stuck")
            .status(DocumentStatus.PROCESSING)
            .statusChangedAt(LocalDateTime.now().minusDays(2)).build();
        FinancialDocument failed = FinancialDocument.builder()
            .id(2L).userId(USER).source(DocumentSource.GMAIL).sourceRef("msg-failed")
            .status(DocumentStatus.FAILED).attempts(3).lastError("parser threw").build();

        when(repo.findStalled(any())).thenReturn(List.of(stalled));
        when(repo.findByUserIdAndStatus(USER, DocumentStatus.FAILED)).thenReturn(List.of(failed));

        List<ReconciliationIssue> issues = new StalledDocumentCheck(repo).run(USER);

        assertThat(issues).hasSize(2);
        assertThat(issues).extracting(ReconciliationIssue::getType)
            .containsExactlyInAnyOrder("INGESTION_STALLED_DOCUMENT", "INGESTION_FAILED_DOCUMENT");
    }

    @Test
    @DisplayName("another user's stalled document is not reported to this user")
    void stalledCheckIsScopedToTheUser() {
        FinancialDocumentRepository repo = mock(FinancialDocumentRepository.class);
        when(repo.findStalled(any())).thenReturn(List.of(FinancialDocument.builder()
            .id(9L).userId(999L).source(DocumentSource.GMAIL).sourceRef("other")
            .status(DocumentStatus.PROCESSING)
            .statusChangedAt(LocalDateTime.now().minusDays(2)).build()));
        when(repo.findByUserIdAndStatus(USER, DocumentStatus.FAILED)).thenReturn(List.of());

        assertThat(new StalledDocumentCheck(repo).run(USER)).isEmpty();
    }

    @Test
    @DisplayName("a review backlog is one aggregate issue, not one per item")
    void reviewBacklogAggregates() {
        EmailReviewItemRepository repo = mock(EmailReviewItemRepository.class);
        List<EmailReviewItem> old = List.of(
            item(LocalDateTime.now().minusDays(40)),
            item(LocalDateTime.now().minusDays(30)),
            item(LocalDateTime.now().minusDays(20)));
        when(repo.findByUserIdAndStatusOrderByCreatedAtDesc(USER, ReviewStatus.PENDING))
            .thenReturn(old);

        List<ReconciliationIssue> issues = new ReviewBacklogCheck(repo).run(USER);

        // Fifty identical issues would bury every other finding in the report.
        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst().getDescription()).contains("3 review item(s)");
    }

    @Test
    void recentReviewItemsAreNotABacklog() {
        EmailReviewItemRepository repo = mock(EmailReviewItemRepository.class);
        when(repo.findByUserIdAndStatusOrderByCreatedAtDesc(USER, ReviewStatus.PENDING))
            .thenReturn(List.of(item(LocalDateTime.now().minusDays(1))));

        assertThat(new ReviewBacklogCheck(repo).run(USER)).isEmpty();
    }

    @Test
    @DisplayName("one exploding check does not sink the whole report")
    void aFailingCheckBecomesAnIssueAndTheRestStillRun() {
        ReconciliationCheck exploding = new ReconciliationCheck() {
            @Override public String id() { return "BOOM"; }
            @Override public String domain() { return "LEDGER"; }
            @Override public String description() { return "always throws"; }
            @Override public List<ReconciliationIssue> run(Long userId) {
                throw new IllegalStateException("db unavailable");
            }
        };
        ReconciliationCheck healthy = new ReconciliationCheck() {
            @Override public String id() { return "OK"; }
            @Override public String domain() { return "LEDGER"; }
            @Override public String description() { return "finds one thing"; }
            @Override public List<ReconciliationIssue> run(Long userId) {
                return List.of(ReconciliationIssue.builder()
                    .domain("LEDGER").type("FOUND").severity("LOW").description("x").build());
            }
        };

        List<ReconciliationIssue> issues =
            new ReconciliationRegistry(List.of(exploding, healthy)).runAll(USER);

        // A report that returns nothing because of one bug looks exactly like a clean bill of
        // health — which is the most dangerous possible output for this feature.
        assertThat(issues).hasSize(2);
        assertThat(issues).extracting(ReconciliationIssue::getType)
            .containsExactlyInAnyOrder("CHECK_FAILED", "FOUND");
        assertThat(issues).anyMatch(i -> i.getDescription().contains("nothing is known"));
    }

    @Test
    void theCheckSetIsEnumerable() {
        ReconciliationRegistry registry = new ReconciliationRegistry(List.of(
            new UnappliedTransferCheck(mock(LedgerTransferRepository.class)),
            new NegativeCashCheck(mock(CashAccountRepository.class))));

        assertThat(registry.checkCount()).isEqualTo(2);
        assertThat(registry.describeChecks())
            .containsKeys("LEDGER_UNAPPLIED_TRANSFER", "LEDGER_NEGATIVE_CASH");
    }

    private static EmailReviewItem item(LocalDateTime created) {
        EmailReviewItem i = new EmailReviewItem();
        i.setCreatedAt(created);
        i.setStatus(ReviewStatus.PENDING);
        return i;
    }
}
