package com.marketai.gmail.service;

import com.marketai.auth.entity.User;
import com.marketai.document.identity.ReferenceHarvester;
import com.marketai.gmail.entity.DuplicateState;
import com.marketai.gmail.entity.ImportedTransactionFingerprint;
import com.marketai.gmail.parser.ParsedEmail;
import com.marketai.gmail.repository.ImportedTransactionFingerprintRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Exercises the weighted tier as wired into the live importer: a high-confidence match must
 * skip persisting a new expense (it is corroboration, not a new event), while a mid-confidence
 * match must still book the transaction — per spec, an uncertain candidate is never silently
 * discarded — just flagged NEEDS_REVIEW.
 */
class WeightedDuplicateImportTest {

    private static final Long USER = 12L;

    private com.marketai.expense.repository.ExpenseRepository expenseRepo;
    private ImportedTransactionFingerprintRepository fingerprintRepo;
    private TransactionMatchScorer matchScorer;
    private ParsedEmailImporter importer;

    @BeforeEach
    void setUp() {
        expenseRepo = mock(com.marketai.expense.repository.ExpenseRepository.class);
        fingerprintRepo = mock(ImportedTransactionFingerprintRepository.class);
        matchScorer = mock(TransactionMatchScorer.class);

        importer = new ParsedEmailImporter(
            mock(com.marketai.tracking.service.TrackingService.class),
            mock(com.marketai.portfolio.service.PortfolioService.class),
            mock(com.marketai.income.repository.IncomeRepository.class),
            expenseRepo,
            mock(com.marketai.card.repository.CreditCardRepository.class),
            mock(com.marketai.card.repository.CardStatementRepository.class),
            mock(com.marketai.card.repository.CardPaymentRepository.class),
            mock(com.marketai.tracking.repository.FixedDepositRepository.class),
            mock(com.marketai.tracking.repository.RecurringDepositRepository.class),
            new TransactionFingerprinter(), fingerprintRepo, new ReferenceHarvester(), matchScorer, mock(com.marketai.rent.service.RentService.class));

        when(fingerprintRepo.existsByUserIdAndFingerprint(any(), anyString())).thenReturn(false);
        when(fingerprintRepo.findFirstByUserIdAndExternalRefAndExternalRefType(any(), any(), any()))
            .thenReturn(Optional.empty());
        when(fingerprintRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(expenseRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(expenseRepo.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(any(), any(), any()))
            .thenReturn(java.util.List.of());
    }

    private User user() {
        User u = new User();
        u.setId(USER);
        return u;
    }

    private ParsedEmail expense(BigDecimal amount) {
        return ParsedEmail.builder()
            .type(ParsedEmail.Type.EXPENSE)
            .amount(amount).tradeDate(LocalDate.of(2026, 9, 10))
            .merchant("Amazon").cardLast4("1234")
            .category("Shopping")
            .sourceDescription("Amazon purchase")
            .build();
    }

    @Test
    @DisplayName("a high-confidence weighted match skips booking a new expense and is recorded as MATCHED_TO_EXISTING")
    void highConfidenceMatchSkipsBooking() throws Exception {
        ImportedTransactionFingerprint priorStatementLine = ImportedTransactionFingerprint.builder().id(77L).build();
        when(matchScorer.findBestMatch(eq(USER), any(), any()))
            .thenReturn(Optional.of(new TransactionMatchScorer.ScoredMatch(priorStatementLine, 0.90)));

        importer.importParsedEmail(USER, user(), expense(new BigDecimal("4500.00")), "msg-a");

        verify(expenseRepo, never()).save(any());

        ArgumentCaptor<ImportedTransactionFingerprint> captor = ArgumentCaptor.forClass(ImportedTransactionFingerprint.class);
        verify(fingerprintRepo).save(captor.capture());
        assertThat(captor.getValue().getDuplicateState()).isEqualTo(DuplicateState.MATCHED_TO_EXISTING.name());
        assertThat(captor.getValue().getMatchedFingerprintId()).isEqualTo(77L);
        assertThat(captor.getValue().getMatchConfidence()).isEqualTo(0.90);
    }

    @Test
    @DisplayName("a mid-confidence weighted match still books the expense, flagged NEEDS_REVIEW rather than discarded")
    void midConfidenceMatchStillBooksButFlags() throws Exception {
        ImportedTransactionFingerprint priorLine = ImportedTransactionFingerprint.builder().id(88L).build();
        when(matchScorer.findBestMatch(eq(USER), any(), any()))
            .thenReturn(Optional.of(new TransactionMatchScorer.ScoredMatch(priorLine, 0.50)));

        importer.importParsedEmail(USER, user(), expense(new BigDecimal("4500.00")), "msg-b");

        verify(expenseRepo).save(any());

        ArgumentCaptor<ImportedTransactionFingerprint> captor = ArgumentCaptor.forClass(ImportedTransactionFingerprint.class);
        verify(fingerprintRepo).save(captor.capture());
        assertThat(captor.getValue().getDuplicateState()).isEqualTo(DuplicateState.NEEDS_REVIEW.name());
        assertThat(captor.getValue().getMatchedFingerprintId()).isEqualTo(88L);
    }

    @Test
    @DisplayName("no weighted match at all books the expense normally, flagged NEW")
    void noMatchBooksAsNew() throws Exception {
        when(matchScorer.findBestMatch(eq(USER), any(), any())).thenReturn(Optional.empty());

        importer.importParsedEmail(USER, user(), expense(new BigDecimal("4500.00")), "msg-c");

        verify(expenseRepo).save(any());

        ArgumentCaptor<ImportedTransactionFingerprint> captor = ArgumentCaptor.forClass(ImportedTransactionFingerprint.class);
        verify(fingerprintRepo).save(captor.capture());
        assertThat(captor.getValue().getDuplicateState()).isEqualTo(DuplicateState.NEW.name());
        assertThat(captor.getValue().getMatchedFingerprintId()).isNull();
    }
}
