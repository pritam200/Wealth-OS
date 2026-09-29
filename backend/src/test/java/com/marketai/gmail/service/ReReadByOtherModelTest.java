package com.marketai.gmail.service;

import com.marketai.auth.entity.User;
import com.marketai.document.identity.ReferenceHarvester;
import com.marketai.gmail.entity.ImportedTransactionFingerprint;
import com.marketai.gmail.parser.ParsedEmail;
import com.marketai.gmail.repository.ImportedTransactionFingerprintRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Changing the model or prompt must never re-book or overwrite what an earlier read recorded. A
 * line a new read gives identically is a duplicate (tier 2); one that differs goes to a person.
 */
class ReReadByOtherModelTest {

    private ImportedTransactionFingerprintRepository fingerprintRepo;
    private com.marketai.income.repository.IncomeRepository incomeRepo;
    private ParsedEmailImporter importer;

    private static final Long USER = 5L;
    private static final String NEW_READ = "ollama:qwen2.5:14b/transaction-extraction-v1";

    @BeforeEach
    void setUp() {
        fingerprintRepo = mock(ImportedTransactionFingerprintRepository.class);
        incomeRepo = mock(com.marketai.income.repository.IncomeRepository.class);
        importer = new ParsedEmailImporter(
            mock(com.marketai.tracking.service.TrackingService.class),
            mock(com.marketai.portfolio.service.PortfolioService.class),
            incomeRepo,
            mock(com.marketai.expense.repository.ExpenseRepository.class),
            mock(com.marketai.card.repository.CreditCardRepository.class),
            mock(com.marketai.card.repository.CardStatementRepository.class),
            mock(com.marketai.card.repository.CardPaymentRepository.class),
            mock(com.marketai.tracking.repository.FixedDepositRepository.class),
            mock(com.marketai.tracking.repository.RecurringDepositRepository.class),
            new TransactionFingerprinter(),
            fingerprintRepo,
            new ReferenceHarvester(),
            mock(TransactionMatchScorer.class), mock(com.marketai.rent.service.RentService.class));
        when(fingerprintRepo.findFirstByUserIdAndFingerprint(any(), anyString())).thenReturn(Optional.empty());
        when(fingerprintRepo.findFirstByUserIdAndExternalRefAndExternalRefType(any(), any(), any())).thenReturn(Optional.empty());
        when(fingerprintRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static ParsedEmail interest(boolean confirmed) {
        return ParsedEmail.builder()
            .type(ParsedEmail.Type.INCOME).incomeSource("Interest")
            .amount(new BigDecimal("1250.00")).tradeDate(LocalDate.of(2026, 9, 12))
            .sourceDescription("interest credit")
            .extractionMethod("PDF_LLM").extractionVersion(NEW_READ)
            .readStartedAt(LocalDateTime.of(2026, 9, 29, 10, 0))
            .userConfirmed(confirmed)
            .build();
    }

    @Test
    @DisplayName("a line that differs from an earlier read by another model goes to review, and nothing is booked")
    void differingLineFromANewModelIsReviewed() {
        when(fingerprintRepo.existsReadByOtherVersion(eq(USER), eq("msg-1"), eq(NEW_READ), any())).thenReturn(true);

        assertThatThrownBy(() -> importer.importParsedEmail(USER, new User(), interest(false), "msg-1", null))
            .isInstanceOf(ImportRejectedException.class)
            .hasMessageContaining("different model or prompt");
        verify(incomeRepo, never()).save(any());
        verify(fingerprintRepo, never()).save(any());
    }

    @Test
    @DisplayName("the same read (or a first read) books normally and records which model and prompt read it")
    void sameReadBooksAndRecordsTheVersion() throws Exception {
        when(fingerprintRepo.existsReadByOtherVersion(any(), any(), any(), any())).thenReturn(false);

        assertThat(importer.importParsedEmail(USER, new User(), interest(false), "msg-1", null))
            .isEqualTo(ParsedEmailImporter.ImportOutcome.IMPORTED);
        ArgumentCaptor<ImportedTransactionFingerprint> row = ArgumentCaptor.forClass(ImportedTransactionFingerprint.class);
        verify(fingerprintRepo).save(row.capture());
        assertThat(row.getValue().getExtractionVersion()).isEqualTo(NEW_READ);
    }

    @Test
    @DisplayName("a person accepting the line from review is not asked again")
    void confirmedLineIsNotHeldBack() throws Exception {
        when(fingerprintRepo.existsReadByOtherVersion(any(), any(), any(), any())).thenReturn(true);

        assertThat(importer.importParsedEmail(USER, new User(), interest(true), "msg-1", null))
            .isEqualTo(ParsedEmailImporter.ImportOutcome.IMPORTED);
    }
}
