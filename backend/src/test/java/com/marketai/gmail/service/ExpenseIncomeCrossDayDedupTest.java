package com.marketai.gmail.service;

import com.marketai.auth.entity.User;
import com.marketai.document.identity.ReferenceHarvester;
import com.marketai.expense.entity.Expense;
import com.marketai.expense.repository.ExpenseRepository;
import com.marketai.gmail.parser.ParsedEmail;
import com.marketai.gmail.repository.ImportedTransactionFingerprintRepository;
import com.marketai.income.entity.Income;
import com.marketai.income.repository.IncomeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A parser that can't extract a transaction date falls back to {@code LocalDate.now()} in
 * {@code importExpense}/{@code importIncome} — so the same email re-synced on three different
 * calendar days previously produced three separate rows, all sharing one {@code sourceEmailId}.
 * Each row landed outside the other two's same-day dedup window ({@code
 * findByUserIdAndExpenseDateBetween(date, date)}), so a live audit of the running database found
 * 33 such expense groups (₹80,727 overstated) and 2 income groups. The fix checks
 * {@code sourceEmailId} directly, independent of which day each attempt happened to run on.
 */
class ExpenseIncomeCrossDayDedupTest {

    private static final Long USER = 12L;

    private ExpenseRepository expenseRepo;
    private IncomeRepository incomeRepo;
    private ImportedTransactionFingerprintRepository fingerprintRepo;
    private ParsedEmailImporter importer;

    @BeforeEach
    void setUp() {
        expenseRepo = mock(ExpenseRepository.class);
        incomeRepo = mock(IncomeRepository.class);
        fingerprintRepo = mock(ImportedTransactionFingerprintRepository.class);

        importer = new ParsedEmailImporter(
            mock(com.marketai.tracking.service.TrackingService.class),
            mock(com.marketai.portfolio.service.PortfolioService.class),
            incomeRepo,
            expenseRepo,
            mock(com.marketai.card.repository.CreditCardRepository.class),
            mock(com.marketai.card.repository.CardStatementRepository.class),
            mock(com.marketai.card.repository.CardPaymentRepository.class),
            mock(com.marketai.tracking.repository.FixedDepositRepository.class),
            mock(com.marketai.tracking.repository.RecurringDepositRepository.class),
            new TransactionFingerprinter(), fingerprintRepo, new ReferenceHarvester(),
            mock(TransactionMatchScorer.class), mock(com.marketai.rent.service.RentService.class));

        when(fingerprintRepo.existsByUserIdAndFingerprint(any(), anyString())).thenReturn(false);
        when(fingerprintRepo.findFirstByUserIdAndExternalRefAndExternalRefType(any(), any(), any()))
            .thenReturn(Optional.empty());
        when(fingerprintRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(expenseRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(incomeRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        // No same-day rows exist — simulates the fallback date landing on a fresh calendar day
        // each time, which is exactly the condition that let the bug through.
        when(expenseRepo.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(any(), any(), any()))
            .thenReturn(List.of());
        when(incomeRepo.findByUserIdAndIncomeDateBetweenOrderByIncomeDateDesc(any(), any(), any()))
            .thenReturn(List.of());
    }

    private ParsedEmail expenseWithNoDate(String amount) {
        return ParsedEmail.builder()
            .type(ParsedEmail.Type.EXPENSE)
            .merchant("CRED")
            .category("Bills")
            .amount(new BigDecimal(amount))
            .sourceDescription("CRED bill payment")
            .build(); // no tradeDate — forces the LocalDate.now() fallback
    }

    private ParsedEmail incomeWithNoDate(String amount) {
        return ParsedEmail.builder()
            .type(ParsedEmail.Type.INCOME)
            .incomeSource("Other")
            .amount(new BigDecimal(amount))
            .sourceDescription("misc credit")
            .build();
    }

    @Test
    @DisplayName("re-syncing the same undated expense email on a later calendar day is refused, "
        + "not booked as a second expense")
    void sameMessageDifferentFallbackDayIsNotDuplicated() throws Exception {
        // Simulates: this exact email already produced a row (on some earlier fallback date).
        when(expenseRepo.existsByUserIdAndSourceEmailId(USER, "msg-repeat")).thenReturn(true);

        importer.importParsedEmail(USER, new User(), expenseWithNoDate("24.00"), "msg-repeat");

        verify(expenseRepo, never()).save(any(Expense.class));
    }

    @Test
    @DisplayName("the first sync of an undated expense email still books normally")
    void firstSyncOfUndatedExpenseStillImports() throws Exception {
        when(expenseRepo.existsByUserIdAndSourceEmailId(USER, "msg-new")).thenReturn(false);

        importer.importParsedEmail(USER, new User(), expenseWithNoDate("24.00"), "msg-new");

        verify(expenseRepo).save(any(Expense.class));
    }

    @Test
    @DisplayName("re-syncing the same undated income email on a later calendar day is refused")
    void sameMessageDifferentFallbackDayIsNotDuplicatedForIncome() throws Exception {
        when(incomeRepo.existsByUserIdAndSourceEmailId(USER, "msg-income-repeat")).thenReturn(true);

        importer.importParsedEmail(USER, new User(), incomeWithNoDate("33.00"), "msg-income-repeat");

        verify(incomeRepo, never()).save(any(Income.class));
    }
}
