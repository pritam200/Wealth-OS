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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Transaction immutability and conflict detection.
 *
 * A UTR identifies exactly one payment, so two documents quoting it with different amounts
 * cannot both be right. The importer must keep the already-verified record and refuse the
 * second — but refusing *silently*, which is what it did, leaves a real discrepancy invisible.
 * A conflict the system noticed and never mentioned is worse than one it never noticed, because
 * the user has no reason to go looking.
 */
class TransactionConflictTest {

    private static final Long USER = 3L;
    private static final String UTR_BODY = "Credited. UTR No: HDFCN52026091200123456";

    private ImportedTransactionFingerprintRepository fpRepo;
    private com.marketai.income.repository.IncomeRepository incomeRepo;
    private ParsedEmailImporter importer;

    @BeforeEach
    void setUp() {
        fpRepo = mock(ImportedTransactionFingerprintRepository.class);
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
            new TransactionFingerprinter(), fpRepo, new ReferenceHarvester(),
            mock(TransactionMatchScorer.class));

        when(fpRepo.existsByUserIdAndFingerprint(any(), anyString())).thenReturn(false);
        when(fpRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(incomeRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private ParsedEmail income(String amount) {
        return ParsedEmail.builder()
            .type(ParsedEmail.Type.INCOME).incomeSource("Interest")
            .amount(new BigDecimal(amount))
            .tradeDate(LocalDate.of(2026, 9, 12))
            .sourceDescription("interest credit")
            .build();
    }

    @Test
    @DisplayName("the same UTR with a different amount is recorded as a conflict, not skipped silently")
    void differingContentOnSameReferenceRaisesConflict() {
        ImportedTransactionFingerprint prior = ImportedTransactionFingerprint.builder()
            .id(99L).userId(USER).fingerprint("a-different-content-hash")
            .externalRef("HDFCN52026091200123456").externalRefType("UTR")
            .conflictDetected(false).build();

        when(fpRepo.findFirstByUserIdAndExternalRefAndExternalRefType(
                USER, "HDFCN52026091200123456", "UTR")).thenReturn(Optional.of(prior));

        assertThatNoImportHappens(() -> importer.importParsedEmail(
            USER, new User(), income("5012.50"), "msg-2", UTR_BODY));

        ArgumentCaptor<ImportedTransactionFingerprint> saved =
            ArgumentCaptor.forClass(ImportedTransactionFingerprint.class);
        verify(fpRepo).save(saved.capture());

        assertThat(saved.getValue().isConflictDetected()).isTrue();
        assertThat(saved.getValue().getConflictDetail())
            .contains("UTR")
            .contains("different details")
            .contains("originally imported record was kept");
    }

    @Test
    @DisplayName("the identical transaction arriving twice is a plain duplicate, not a conflict")
    void identicalContentIsNotAConflict() {
        // Same UTR *and* same content — a resend or a forward. Nothing disagrees, so raising a
        // conflict here would cry wolf on the most common case there is.
        ParsedEmail pe = income("5000.00");
        String sameFingerprint = new TransactionFingerprinter().fingerprint(pe);

        ImportedTransactionFingerprint prior = ImportedTransactionFingerprint.builder()
            .id(99L).userId(USER).fingerprint(sameFingerprint)
            .externalRef("HDFCN52026091200123456").externalRefType("UTR")
            .conflictDetected(false).build();

        when(fpRepo.findFirstByUserIdAndExternalRefAndExternalRefType(
                USER, "HDFCN52026091200123456", "UTR")).thenReturn(Optional.of(prior));

        assertThatNoImportHappens(() -> importer.importParsedEmail(
            USER, new User(), pe, "msg-3", UTR_BODY));

        verify(fpRepo, never()).save(any());
    }

    @Test
    @DisplayName("an already-flagged conflict is not re-flagged on every later sync")
    void conflictIsNotRaisedRepeatedly() {
        ImportedTransactionFingerprint prior = ImportedTransactionFingerprint.builder()
            .id(99L).userId(USER).fingerprint("some-other-hash")
            .externalRef("HDFCN52026091200123456").externalRefType("UTR")
            .conflictDetected(true).conflictDetail("already flagged").build();

        when(fpRepo.findFirstByUserIdAndExternalRefAndExternalRefType(
                USER, "HDFCN52026091200123456", "UTR")).thenReturn(Optional.of(prior));

        assertThatNoImportHappens(() -> importer.importParsedEmail(
            USER, new User(), income("5012.50"), "msg-4", UTR_BODY));

        // Re-saving on every sync would churn the row and reset any triage state on it.
        verify(fpRepo, never()).save(any());
    }

    @Test
    @DisplayName("a conflicting document never reaches the ledger")
    void conflictingDocumentIsNotBooked() {
        ImportedTransactionFingerprint prior = ImportedTransactionFingerprint.builder()
            .id(99L).userId(USER).fingerprint("different")
            .externalRef("HDFCN52026091200123456").externalRefType("UTR")
            .conflictDetected(false).build();

        when(fpRepo.findFirstByUserIdAndExternalRefAndExternalRefType(any(), any(), any()))
            .thenReturn(Optional.of(prior));

        assertThatNoImportHappens(() -> importer.importParsedEmail(
            USER, new User(), income("9999.00"), "msg-5", UTR_BODY));

        // Booking it would double-count; overwriting would destroy verified history.
        verify(incomeRepo, never()).save(any());
    }

    private void assertThatNoImportHappens(ThrowingRunnable r) {
        try { r.run(); } catch (Exception e) { throw new AssertionError("import threw", e); }
    }

    @FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }
}
