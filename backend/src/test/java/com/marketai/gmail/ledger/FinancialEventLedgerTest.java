package com.marketai.gmail.ledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FinancialEventLedgerTest {

    private EmailFinancialEventRepository repo;
    private FinancialEventLedger ledger;

    @BeforeEach
    void setUp() {
        repo = mock(EmailFinancialEventRepository.class);
        ledger = new FinancialEventLedger(repo);
        FinancialEventLedger.resetFailures();
    }

    private static EmailFinancialEvent event(EventState state) {
        return EmailFinancialEvent.builder().userId(1L).gmailMessageId("m").eventKey("k").itemIndex(0)
            .state(state).reason("r").build();
    }

    @Test
    @DisplayName("a re-read finding a booked event 'already recorded' keeps it IMPORTED")
    void importedIsNeverDowngraded() {
        EmailFinancialEvent existing = event(EventState.IMPORTED);
        existing.setId(5L);
        when(repo.findByUserIdAndGmailMessageIdAndEventKey(1L, "m", "k")).thenReturn(Optional.of(existing));

        ledger.record(event(EventState.DUPLICATE_OF_EXISTING));

        verify(repo).save(argThat(e -> e.getId() == 5L && e.getState() == EventState.IMPORTED && e.getLastSeenAt() != null));
    }

    @Test
    @DisplayName("a person's decision is never overridden by a later read")
    void userDecisionStands() {
        EmailFinancialEvent existing = event(EventState.RESOLVED);
        existing.setResolvedBy(EmailFinancialEvent.BY_USER);
        when(repo.findByUserIdAndGmailMessageIdAndEventKey(1L, "m", "k")).thenReturn(Optional.of(existing));

        ledger.record(event(EventState.REQUIRES_REVIEW));

        verify(repo).save(argThat(e -> e.getState() == EventState.RESOLVED));
    }

    @Test
    @DisplayName("an open event takes the latest reading, keeping its row")
    void openEventIsUpdated() {
        EmailFinancialEvent existing = event(EventState.REQUIRES_REVIEW);
        existing.setId(9L);
        when(repo.findByUserIdAndGmailMessageIdAndEventKey(1L, "m", "k")).thenReturn(Optional.of(existing));

        ledger.record(event(EventState.IMPORTED));

        verify(repo).save(argThat(e -> e.getId() == 9L && e.getState() == EventState.IMPORTED));
    }

    @Test
    @DisplayName("a failed write is counted, never thrown into the import")
    void failuresAreCounted() {
        when(repo.findByUserIdAndGmailMessageIdAndEventKey(any(), any(), any())).thenThrow(new RuntimeException("db down"));

        ledger.record(event(EventState.IMPORTED));

        assertThat(FinancialEventLedger.failures()).isEqualTo(1);
    }

    @Test
    @DisplayName("a review decision accounts for the events behind the item")
    void reviewDecision() {
        EmailFinancialEvent open = event(EventState.REQUIRES_REVIEW);
        when(repo.findByUserIdAndGmailMessageIdAndItemIndex(1L, "m", 0)).thenReturn(List.of(open));

        ledger.onReviewDecision(1L, "m", 0, false, "not mine");

        assertThat(open.getState()).isEqualTo(EventState.RESOLVED);
        assertThat(open.getResolvedBy()).isEqualTo(EmailFinancialEvent.BY_USER);
        assertThat(open.getReason()).contains("not mine");
    }

    @Test
    @DisplayName("only reconciliation rows can be marked resolved directly; review items are decided in the queue")
    void acknowledge() {
        EmailFinancialEvent review = event(EventState.REQUIRES_REVIEW);
        review.setId(3L);
        when(repo.findById(3L)).thenReturn(Optional.of(review));
        assertThatThrownBy(() -> ledger.acknowledge(1L, 3L, null)).hasMessageContaining("review queue");

        EmailFinancialEvent recon = event(EventState.RECONCILIATION_REQUIRED);
        recon.setId(4L);
        when(repo.findById(4L)).thenReturn(Optional.of(recon));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        assertThatThrownBy(() -> ledger.acknowledge(2L, 4L, null)).hasMessageContaining("not found");
        assertThat(ledger.acknowledge(1L, 4L, "checked").getState()).isEqualTo(EventState.RESOLVED);
    }

    @Test
    @DisplayName("card numbers, account numbers and PAN are masked in stored evidence; dates and amounts are kept")
    void masking() {
        assertThat(FinancialEventLedger.mask("Card 4111 1111 1111 1234 spent 1,200.00 on 10-01-2026"))
            .isEqualTo("Card ••••1234 spent 1,200.00 on 10-01-2026");
        assertThat(FinancialEventLedger.mask("A/c 123456789012 credited")).isEqualTo("A/c ••••9012 credited");
        assertThat(FinancialEventLedger.mask("PAN ABCDE1234F")).isEqualTo("PAN ••••••••••");
    }
}
