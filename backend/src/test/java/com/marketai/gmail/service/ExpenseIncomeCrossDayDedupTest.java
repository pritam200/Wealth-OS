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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Expense/income duplicate handling, against an in-memory ledger so multi-line statements and
 * re-syncs are exercised end to end through the importer.
 *
 * <p>History: an undated item used to be booked on "today", so the same email re-synced on three
 * days produced three rows; the fix for that was an "any row from this email already exists"
 * shortcut — which in turn dropped every line of a multi-line statement after the first. Now:
 * undated items are rejected to review, and same-email duplicates are counted per line.
 */
class ExpenseIncomeCrossDayDedupTest {

    private static final Long USER = 12L;
    private static final LocalDate DAY = LocalDate.of(2026, 9, 10);

    private final List<Expense> expenses = new ArrayList<>();
    private final List<Income> incomes = new ArrayList<>();
    private final Map<String, com.marketai.gmail.entity.ImportedTransactionFingerprint> fingerprints = new HashMap<>();
    private ParsedEmailImporter importer;

    @BeforeEach
    void setUp() {
        ExpenseRepository expenseRepo = mock(ExpenseRepository.class);
        IncomeRepository incomeRepo = mock(IncomeRepository.class);
        ImportedTransactionFingerprintRepository fingerprintRepo = mock(ImportedTransactionFingerprintRepository.class);

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

        when(fingerprintRepo.existsByUserIdAndFingerprint(any(), anyString()))
            .thenAnswer(i -> fingerprints.containsKey(i.<String>getArgument(1)));
        when(fingerprintRepo.findFirstByUserIdAndFingerprint(any(), anyString()))
            .thenAnswer(i -> Optional.ofNullable(fingerprints.get(i.<String>getArgument(1))));
        when(fingerprintRepo.findFirstByUserIdAndExternalRefAndExternalRefType(any(), any(), any()))
            .thenReturn(Optional.empty());
        when(fingerprintRepo.save(any())).thenAnswer(i -> {
            var row = i.<com.marketai.gmail.entity.ImportedTransactionFingerprint>getArgument(0);
            fingerprints.put(row.getFingerprint(), row);
            return i.getArgument(0);
        });
        when(expenseRepo.save(any())).thenAnswer(i -> { expenses.add(i.getArgument(0)); return i.getArgument(0); });
        when(incomeRepo.save(any())).thenAnswer(i -> { incomes.add(i.getArgument(0)); return i.getArgument(0); });
        when(expenseRepo.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(any(), any(), any()))
            .thenAnswer(i -> expenses.stream().filter(e -> e.getExpenseDate().equals(i.getArgument(1))).toList());
        when(incomeRepo.findByUserIdAndIncomeDateBetweenOrderByIncomeDateDesc(any(), any(), any()))
            .thenAnswer(i -> incomes.stream().filter(e -> e.getIncomeDate().equals(i.getArgument(1))).toList());
    }

    private static ParsedEmail expense(String merchant, String amount, LocalDate date, int occurrence) {
        return ParsedEmail.builder()
            .type(ParsedEmail.Type.EXPENSE)
            .merchant(merchant)
            .category("Food Delivery")
            .amount(new BigDecimal(amount))
            .tradeDate(date)
            .sourceDescription(merchant + " ₹" + amount)
            .occurrenceInSource(occurrence)
            .build();
    }

    private void importAll(String msgId, ParsedEmail... lines) throws Exception {
        for (ParsedEmail pe : lines) importer.importParsedEmail(USER, new User(), pe, msgId);
    }

    @Test
    @DisplayName("an undated expense is rejected to review, never booked on today's date")
    void undatedExpenseIsRejected() {
        assertThatThrownBy(() -> importAll("msg-1", expense("Swiggy", "24.00", null, 0)))
            .isInstanceOf(ImportRejectedException.class)
            .hasMessageContaining("date");
        assertThat(expenses).isEmpty();
    }

    @Test
    @DisplayName("an undated credit is rejected to review too")
    void undatedIncomeIsRejected() {
        ParsedEmail credit = ParsedEmail.builder().type(ParsedEmail.Type.INCOME).incomeSource("Other")
            .amount(new BigDecimal("33.00")).sourceDescription("misc credit").build();
        assertThatThrownBy(() -> importer.importParsedEmail(USER, new User(), credit, "msg-2"))
            .isInstanceOf(ImportRejectedException.class);
        assertThat(incomes).isEmpty();
    }

    @Test
    @DisplayName("every line of a multi-line statement is booked, not just the first")
    void everyStatementLineIsBooked() throws Exception {
        importAll("stmt-1",
            expense("Swiggy", "250.00", DAY, 0),
            expense("Uber", "180.00", DAY, 0),
            expense("Amazon", "999.00", DAY.plusDays(1), 0));

        assertThat(expenses).hasSize(3);
    }

    @Test
    @DisplayName("two identical lines in one statement are two transactions")
    void identicalLinesAreBothBooked() throws Exception {
        importAll("stmt-2",
            expense("Blue Tokai", "250.00", DAY, 0),
            expense("Blue Tokai", "250.00", DAY, 1));

        assertThat(expenses).hasSize(2);
    }

    @Test
    @DisplayName("same amount and day at different merchants in one statement are both booked")
    void sameAmountDifferentMerchantsAreBothBooked() throws Exception {
        // Occurrence is numbered on (debit, amount, date), exactly what the same-email check counts.
        importAll("stmt-3",
            expense("Swiggy", "100.00", DAY, 0),
            expense("Zomato", "100.00", DAY, 1));

        assertThat(expenses).hasSize(2);
    }

    @Test
    @DisplayName("re-syncing a statement books nothing a second time")
    void reSyncBooksNothingAgain() throws Exception {
        ParsedEmail[] lines = {
            expense("Blue Tokai", "250.00", DAY, 0),
            expense("Blue Tokai", "250.00", DAY, 1),
            expense("Uber", "180.00", DAY, 0)};
        importAll("stmt-4", lines);
        importAll("stmt-4", lines);

        assertThat(expenses).hasSize(3);
    }

    @Test
    @DisplayName("re-sync where the extractor names the merchant differently still books nothing twice")
    void reSyncWithMerchantVariantIsStillRecognised() throws Exception {
        importAll("stmt-5", expense("Swiggy", "250.00", DAY, 0));
        // Different fingerprint (merchant text changed), same email + amount + date + line number.
        importAll("stmt-5", expense("SWIGGY BANGALORE", "250.00", DAY, 0));

        assertThat(expenses).hasSize(1);
    }

    @Test
    @DisplayName("a lookalike from another email goes to review instead of being silently dropped")
    void lookalikeFromAnotherEmailGoesToReview() throws Exception {
        importAll("alert-1", expense("Blue Tokai", "250.00", DAY, 0));

        // Not byte-identical (the second alert names the card), so this is the similarity check.
        ParsedEmail similar = expense("Blue Tokai", "250.00", DAY, 0).toBuilder().cardLast4("4321").build();
        assertThatThrownBy(() -> importAll("alert-2", similar))
            .isInstanceOf(ImportRejectedException.class)
            .hasMessageContaining("already recorded from another source");
        assertThat(expenses).hasSize(1);
    }

    @Test
    @DisplayName("once a person confirms the lookalike is separate, it is booked")
    void confirmedLookalikeIsBooked() throws Exception {
        importAll("alert-1", expense("Blue Tokai", "250.00", DAY, 0));

        ParsedEmail confirmed = expense("Blue Tokai", "250.00", DAY, 0).toBuilder()
            .sourceDescription("Human-reviewed: EXPENSE").userConfirmed(true).build();
        importAll("alert-2", confirmed);

        assertThat(expenses).hasSize(2);
    }

    @Test
    @DisplayName("an identical expense from another email goes to review, not silently merged")
    void identicalFromAnotherEmailGoesToReview() throws Exception {
        importAll("alert-1", expense("Blue Tokai", "250.00", DAY, 0));

        assertThatThrownBy(() -> importAll("alert-2", expense("Blue Tokai", "250.00", DAY, 0)))
            .isInstanceOf(ImportRejectedException.class)
            .hasMessageContaining("identical");
        assertThat(expenses).hasSize(1);
    }

    @Test
    @DisplayName("identical alerts carrying different UTRs are two payments — both booked")
    void identicalContentWithDifferentReferencesIsTwoPayments() throws Exception {
        importer.importParsedEmail(USER, new User(), expense("Blue Tokai", "250.00", DAY, 0), "alert-1",
            "Rs 250.00 spent at Blue Tokai. UTR No: 412345678901");
        importer.importParsedEmail(USER, new User(), expense("Blue Tokai", "250.00", DAY, 0).toBuilder().userConfirmed(false).build(),
            "alert-2", "Rs 250.00 spent at Blue Tokai. UTR No: 498765432109");

        assertThat(expenses).hasSize(2);
    }

    @Test
    @DisplayName("a statement's lines are not collapsed onto the one UTR the text happens to contain")
    void statementLinesAreNotTiedToOneReference() throws Exception {
        String statement = "Statement. UTR No: 412345678901 ... more lines";
        importer.importParsedEmail(USER, new User(), expense("Swiggy", "250.00", DAY, 0), "stmt-9", statement);
        importer.importParsedEmail(USER, new User(), expense("Uber", "180.00", DAY, 0), "stmt-9", statement);

        assertThat(expenses).hasSize(2);
    }

    @Test
    @DisplayName("credits follow the same rules: identical lines both booked, re-sync books none")
    void creditsFollowTheSameRules() throws Exception {
        ParsedEmail a = ParsedEmail.builder().type(ParsedEmail.Type.INCOME).incomeSource("Interest")
            .merchant("HDFC Bank").amount(new BigDecimal("41.00")).tradeDate(DAY)
            .sourceDescription("Interest credit").build();
        ParsedEmail b = a.toBuilder().occurrenceInSource(1).build();

        importAll("stmt-6", a, b);
        importAll("stmt-6", a, b);

        assertThat(incomes).hasSize(2);
    }
}
