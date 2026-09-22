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
 * Tier-1 reference dedup on the live import path.
 *
 * The case this exists for: one trade reported twice, once as a bank debit of the gross amount
 * and once as a contract note's net amount. The two share no hashable field — different
 * amounts, different dates, different bytes — so the content fingerprint cannot connect them.
 * Both quote the same UTR.
 */
class ImporterReferenceDedupTest {

    private ImportedTransactionFingerprintRepository fingerprintRepo;
    private com.marketai.portfolio.service.PortfolioService portfolioService;
    private com.marketai.income.repository.IncomeRepository incomeRepo;
    private ParsedEmailImporter importer;

    private static final Long USER = 5L;

    @BeforeEach
    void setUp() {
        fingerprintRepo = mock(ImportedTransactionFingerprintRepository.class);
        portfolioService = mock(com.marketai.portfolio.service.PortfolioService.class);
        incomeRepo = mock(com.marketai.income.repository.IncomeRepository.class);

        importer = new ParsedEmailImporter(
            mock(com.marketai.tracking.service.TrackingService.class),
            portfolioService,
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
            mock(TransactionMatchScorer.class));

        when(fingerprintRepo.existsByUserIdAndFingerprint(any(), anyString())).thenReturn(false);
        when(fingerprintRepo.findFirstByUserIdAndExternalRefAndExternalRefType(any(), any(), any()))
            .thenReturn(Optional.empty());
        when(fingerprintRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private ParsedEmail income(String amount) {
        return ParsedEmail.builder()
            .type(ParsedEmail.Type.INCOME)
            .incomeSource("Interest")
            .amount(new BigDecimal(amount))
            .tradeDate(LocalDate.of(2026, 9, 12))
            .sourceDescription("interest credit")
            .build();
    }

    @Test
    @DisplayName("the harvested reference is stored alongside the fingerprint")
    void referenceIsPersisted() throws Exception {
        importer.importParsedEmail(USER, new User(), income("5000.00"), "msg-1",
            "Interest credited. UTR No: HDFCN52026091200123456");

        ArgumentCaptor<ImportedTransactionFingerprint> saved =
            ArgumentCaptor.forClass(ImportedTransactionFingerprint.class);
        verify(fingerprintRepo).save(saved.capture());

        assertThat(saved.getValue().getExternalRef()).isEqualTo("HDFCN52026091200123456");
        assertThat(saved.getValue().getExternalRefType()).isEqualTo("UTR");
    }

    @Test
    @DisplayName("a second document quoting the same UTR is refused, even with a different amount")
    void sameReferenceDifferentAmountIsDeduped() throws Exception {
        when(fingerprintRepo.findFirstByUserIdAndExternalRefAndExternalRefType(
                USER, "HDFCN52026091200123456", "UTR"))
            .thenReturn(Optional.of(ImportedTransactionFingerprint.builder()
                .userId(USER).fingerprint("prior").build()));

        // Different amount, so the content fingerprint would not have matched.
        importer.importParsedEmail(USER, new User(), income("5012.50"), "msg-2",
            "Interest credited. UTR No: HDFCN52026091200123456");

        // No new financial record is created — that is the dedup guarantee.
        // The one save that does happen marks the *existing* row as conflicting: same UTR,
        // different amount, so the two sources disagree about one payment and a human needs to
        // say which is right. Asserting "never save" here encoded the older behaviour of
        // skipping silently, which hid exactly that discrepancy.
        var saved = org.mockito.ArgumentCaptor.forClass(ImportedTransactionFingerprint.class);
        verify(fingerprintRepo).save(saved.capture());
        assertThat(saved.getValue().isConflictDetected()).isTrue();
        assertThat(saved.getValue().getFingerprint()).isEqualTo("prior");   // the original, not a new row

        // The content-hash gate is never even consulted — tier 1 short-circuits first.
        verify(fingerprintRepo, never()).existsByUserIdAndFingerprint(any(), anyString());
    }

    @Test
    @DisplayName("a different UTR is a different transaction and imports")
    void differentReferenceStillImports() throws Exception {
        importer.importParsedEmail(USER, new User(), income("5000.00"), "msg-3",
            "Interest credited. UTR No: SBIN226091200999");

        // The fingerprint being written proves dedup ran; only the income row proves the money
        // was actually recorded. Asserting the fingerprint alone would pass even if the import
        // silently dropped the ₹5,000.
        verify(fingerprintRepo).save(any());
        assertThat(savedIncomeAmount()).isEqualByComparingTo("5000.00");
    }

    @Test
    @DisplayName("no reference in the document falls back to the content fingerprint unchanged")
    void absentReferenceFallsBackToContentHash() throws Exception {
        importer.importParsedEmail(USER, new User(), income("5000.00"), "msg-4",
            "Interest of Rs 5000 credited to your account.");

        // Behaviour must be identical to before this change when no reference is present.
        verify(fingerprintRepo).existsByUserIdAndFingerprint(eq(USER), anyString());
        verify(fingerprintRepo).save(any());
        assertThat(savedIncomeAmount()).isEqualByComparingTo("5000.00");
    }

    @Test
    @DisplayName("a null document body is safe — reference matching is simply skipped")
    void nullDocumentTextIsSafe() throws Exception {
        importer.importParsedEmail(USER, new User(), income("5000.00"), "msg-5", null);

        verify(fingerprintRepo).existsByUserIdAndFingerprint(eq(USER), anyString());
        verify(fingerprintRepo).save(any());
        assertThat(savedIncomeAmount()).isEqualByComparingTo("5000.00");
    }

    @Test
    @DisplayName("an issuer-scoped reference is NOT used for tier-1 matching")
    void weakReferencesAreNotUsedForIdentity() throws Exception {
        // Two brokers can each mint order "ORD12345". Keying identity on that would merge
        // unrelated transactions, so it must not be stored as the identity reference.
        importer.importParsedEmail(USER, new User(), income("5000.00"), "msg-6",
            "Payment received. Order ID: ORD12345");

        ArgumentCaptor<ImportedTransactionFingerprint> saved =
            ArgumentCaptor.forClass(ImportedTransactionFingerprint.class);
        verify(fingerprintRepo).save(saved.capture());

        assertThat(saved.getValue().getExternalRef()).isNull();
        verify(fingerprintRepo, never())
            .findFirstByUserIdAndExternalRefAndExternalRefType(any(), any(), any());
    }

    /** The amount of the single Income row this import created. */
    private java.math.BigDecimal savedIncomeAmount() {
        ArgumentCaptor<com.marketai.income.entity.Income> captor =
            ArgumentCaptor.forClass(com.marketai.income.entity.Income.class);
        verify(incomeRepo).save(captor.capture());
        return captor.getValue().getAmount();
    }
}
