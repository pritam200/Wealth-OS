package com.marketai.gmail.service;

import com.marketai.auth.entity.User;
import com.marketai.document.identity.ReferenceHarvester;
import com.marketai.gmail.parser.ParsedEmail;
import com.marketai.gmail.repository.ImportedTransactionFingerprintRepository;
import com.marketai.rent.service.RentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A Gmail-detected rent payment must land in the Rent ledger (matched onto an existing
 * schedule placeholder, or booked as a new Rent row), never as a second Expense row — see
 * RentService.matchOrCreateFromGmail and the spec's "duplicate protection" requirement
 * (§2/§19: manual + Gmail sync must not both book the same rent payment).
 */
class RentRoutingImportTest {

    private static final Long USER = 12L;

    private com.marketai.expense.repository.ExpenseRepository expenseRepo;
    private ImportedTransactionFingerprintRepository fingerprintRepo;
    private RentService rentService;
    private ParsedEmailImporter importer;

    @BeforeEach
    void setUp() {
        expenseRepo = mock(com.marketai.expense.repository.ExpenseRepository.class);
        fingerprintRepo = mock(ImportedTransactionFingerprintRepository.class);
        rentService = mock(RentService.class);
        TransactionMatchScorer matchScorer = mock(TransactionMatchScorer.class);

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
            new TransactionFingerprinter(), fingerprintRepo, new ReferenceHarvester(), matchScorer, rentService);

        when(fingerprintRepo.existsByUserIdAndFingerprint(any(), anyString())).thenReturn(false);
        when(fingerprintRepo.findFirstByUserIdAndExternalRefAndExternalRefType(any(), any(), any()))
            .thenReturn(Optional.empty());
        when(fingerprintRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(expenseRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(matchScorer.findBestMatch(any(), any(), any())).thenReturn(Optional.empty());
    }

    private User user() {
        User u = new User();
        u.setId(USER);
        return u;
    }

    @Test
    @DisplayName("a parsed email describing a rent payment routes to RentService, not a second Expense row")
    void rentFlavouredExpenseRoutesToRentService() throws Exception {
        ParsedEmail pe = ParsedEmail.builder()
            .type(ParsedEmail.Type.EXPENSE)
            .amount(new BigDecimal("31000.00")).tradeDate(LocalDate.of(2026, 9, 1))
            .merchant("Landlord").category("Rent")
            .sourceDescription("Rent payment to Landlord")
            .build();

        importer.importParsedEmail(USER, user(), pe, "msg-rent");

        verify(rentService).matchOrCreateFromGmail(USER, new BigDecimal("31000.00"),
            LocalDate.of(2026, 9, 1), "Landlord", "msg-rent", 0);
        verify(expenseRepo, never()).save(any());
    }

    @Test
    @DisplayName("an ordinary (non-rent) expense still books as an Expense, unaffected by RentService")
    void ordinaryExpenseIsUnaffected() throws Exception {
        ParsedEmail pe = ParsedEmail.builder()
            .type(ParsedEmail.Type.EXPENSE)
            .amount(new BigDecimal("450.00")).tradeDate(LocalDate.of(2026, 9, 10))
            .merchant("Amazon").category("Shopping")
            .sourceDescription("Amazon purchase")
            .build();
        when(expenseRepo.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(any(), any(), any()))
            .thenReturn(java.util.List.of());

        importer.importParsedEmail(USER, user(), pe, "msg-amazon");

        verify(expenseRepo).save(any());
        // Only consulted for the same-email line count; never asked to book anything.
        verify(rentService, never()).matchOrCreateFromGmail(any(), any(), any(), any(), any(), anyInt());
        verify(rentService, never()).matchOrCreateFromGmail(any(), any(), any(), any(), any());
    }
}
