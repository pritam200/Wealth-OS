package com.marketai.card.service;

import com.marketai.card.entity.CardStatement;
import com.marketai.card.entity.CardStatementLine;
import com.marketai.card.entity.CardStatementLine.Status;
import com.marketai.card.entity.CreditCard;
import com.marketai.card.repository.CardStatementLineRepository;
import com.marketai.expense.entity.Expense;
import com.marketai.expense.entity.ExpenseCategory;
import com.marketai.expense.repository.ExpenseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CardStatementTallyServiceTest {

    private CardStatementLineRepository lineRepo;
    private ExpenseRepository expenseRepo;
    private CardStatementTallyService service;
    private final List<CardStatementLine> stored = new ArrayList<>();
    private final List<Expense> expenses = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(100);

    private final CreditCard card = CreditCard.builder().id(7L).userId(1L).name("HDFC MoneyBack+").lastFour("4286").build();
    private final CardStatement st = CardStatement.builder().id(50L).cardId(7L).userId(1L)
        .statementDate(LocalDate.of(2026, 10, 15)).totalDue(new BigDecimal("5000")).sourceEmailId("stmt-mail").build();

    @BeforeEach
    void setup() {
        lineRepo = mock(CardStatementLineRepository.class);
        expenseRepo = mock(ExpenseRepository.class);
        service = new CardStatementTallyService(lineRepo, expenseRepo);
        when(lineRepo.existsByStatementId(anyLong())).thenAnswer(i -> !stored.isEmpty());
        when(lineRepo.saveAll(anyList())).thenAnswer(i -> {
            List<CardStatementLine> in = i.getArgument(0);
            for (CardStatementLine l : in) { if (l.getId() == null) { l.setId(ids.incrementAndGet()); stored.add(l); } }
            return in;
        });
        when(lineRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(lineRepo.findByStatementIdOrderByTxnDateAscIdAsc(50L)).thenAnswer(i -> stored);
        when(lineRepo.claimedExpenseIds(1L)).thenAnswer(i -> {
            Set<Long> s = new HashSet<>();
            stored.forEach(l -> { if (l.getMatchedExpenseId() != null) s.add(l.getMatchedExpenseId()); });
            return s;
        });
        when(expenseRepo.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(eq(1L), any(), any())).thenAnswer(i -> {
            LocalDate from = i.getArgument(1), to = i.getArgument(2);
            return expenses.stream().filter(e -> !e.getExpenseDate().isBefore(from) && !e.getExpenseDate().isAfter(to)).toList();
        });
        when(expenseRepo.save(any())).thenAnswer(i -> {
            Expense e = i.getArgument(0);
            e.setId(ids.incrementAndGet());
            expenses.add(e);
            return e;
        });
    }

    private static CardStatementFacts.Line debit(String merchant, String amount, LocalDate d) {
        return new CardStatementFacts.Line(merchant, new BigDecimal(amount), d, "INR", true, "Shopping", merchant + " " + amount);
    }

    private Expense expense(String merchant, String amount, LocalDate d) {
        Expense e = Expense.builder().id(ids.incrementAndGet()).userId(1L).merchant(merchant).description(merchant)
            .amount(new BigDecimal(amount)).expenseDate(d).category(ExpenseCategory.SHOPPING).build();
        expenses.add(e);
        return e;
    }

    @Test
    @DisplayName("A charge the alert already booked is matched; one no sync caught is added as an expense")
    void matchesKnownAndAddsMissing() {
        Expense alert = expense("Amazon", "1499.00", LocalDate.of(2026, 10, 3));
        var s = service.recordAndTally(st, card, List.of(
            debit("AMAZON PAY INDIA", "1499.00", LocalDate.of(2026, 10, 5)),
            debit("SWIGGY", "420.00", LocalDate.of(2026, 10, 7)),
            new CardStatementFacts.Line("PAYMENT RECEIVED", new BigDecimal("3000"), LocalDate.of(2026, 10, 8), "INR", false, null, "PAYMENT 3000")));

        assertThat(s.matched()).isEqualTo(1);
        assertThat(s.added()).isEqualTo(1);
        assertThat(s.credits()).isEqualTo(1);
        assertThat(stored.get(0).getMatchedExpenseId()).isEqualTo(alert.getId());
        Expense added = expenses.get(expenses.size() - 1);
        assertThat(added.getAmount()).isEqualByComparingTo("420.00");
        assertThat(added.getPaymentMethod()).contains("HDFC MoneyBack+").contains("4286");
        assertThat(added.getSourceFingerprint()).startsWith("cardline:");
    }

    @Test
    @DisplayName("Running the tally again books nothing twice, and one expense never ticks off two lines")
    void idempotentAndOneExpensePerLine() {
        expense("Uber", "250.00", LocalDate.of(2026, 10, 2));
        service.recordAndTally(st, card, List.of(
            debit("UBER", "250.00", LocalDate.of(2026, 10, 2)),
            debit("UBER", "250.00", LocalDate.of(2026, 10, 2))));
        int before = expenses.size();
        var again = service.tally(st, card);

        assertThat(expenses).hasSize(before);
        assertThat(again.matched()).isEqualTo(1);
        assertThat(again.added()).isEqualTo(1);
    }

    @Test
    @DisplayName("A card-bill payment is never taken for a charge, and an undated line is left for the user")
    void skipsTransfersAndUndated() {
        Expense bill = expense("HDFC card bill", "800.00", LocalDate.of(2026, 10, 4));
        bill.setCategory(ExpenseCategory.ACCOUNT_TRANSFER);
        var s = service.recordAndTally(st, card, List.of(
            debit("MYNTRA", "800.00", LocalDate.of(2026, 10, 4)),
            debit("UNKNOWN", "99.00", null)));

        assertThat(stored.get(0).getStatus()).isEqualTo(Status.ADDED);
        assertThat(stored.get(1).getStatus()).isEqualTo(Status.MISSING);
        assertThat(s.missing()).isEqualTo(1);
    }

    @Test
    @DisplayName("Taking back an added expense deletes it and leaves the line as ignored")
    void removeAdded() {
        service.recordAndTally(st, card, List.of(debit("ZOMATO", "310.00", LocalDate.of(2026, 10, 9))));
        CardStatementLine line = stored.get(0);
        Long expenseId = line.getMatchedExpenseId();
        when(expenseRepo.findByIdAndUserId(expenseId, 1L)).thenReturn(expenses.stream().filter(e -> e.getId().equals(expenseId)).findFirst());

        service.removeAddedExpense(line);

        verify(expenseRepo).delete(argThat(e -> e.getId().equals(expenseId)));
        assertThat(line.getStatus()).isEqualTo(Status.IGNORED);
        assertThat(line.getMatchedExpenseId()).isNull();
    }

    @Test
    void merchantSimilarity() {
        assertThat(CardStatementTallyService.merchantSimilarity("AMAZON PAY INDIA PVT", "Amazon")).isEqualTo(1.0);
        assertThat(CardStatementTallyService.merchantSimilarity("SWIGGY", "Zomato")).isZero();
    }
}
