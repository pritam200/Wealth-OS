package com.marketai.subscription.service;

import com.marketai.expense.entity.Expense;
import com.marketai.expense.entity.ExpenseCategory;
import com.marketai.expense.repository.ExpenseRepository;
import com.marketai.subscription.dto.SubscriptionCadence;
import com.marketai.subscription.dto.SubscriptionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubscriptionDetectionServiceTest {

    private static final Long USER_ID = 1L;

    private ExpenseRepository expenseRepository;
    private SubscriptionDetectionService service;

    @BeforeEach
    void setup() {
        expenseRepository = mock(ExpenseRepository.class);
        service = new SubscriptionDetectionService(expenseRepository);
    }

    private Expense expense(String merchant, BigDecimal amount, LocalDate date) {
        return Expense.builder()
            .userId(USER_ID).description(merchant).merchant(merchant).amount(amount)
            .category(ExpenseCategory.ENTERTAINMENT).expenseDate(date).build();
    }

    private void stub(List<Expense> expenses) {
        when(expenseRepository.findByUserIdOrderByExpenseDateAsc(anyLong())).thenReturn(expenses);
    }

    @Test
    void threeMonthsOfTheSameMerchantAndAmount_isDetectedAsAMonthlySubscription() {
        List<Expense> expenses = new ArrayList<>();
        expenses.add(expense("Netflix", new BigDecimal("499.00"), LocalDate.of(2026, 6, 15)));
        expenses.add(expense("Netflix", new BigDecimal("499.00"), LocalDate.of(2026, 7, 15)));
        expenses.add(expense("Netflix", new BigDecimal("499.00"), LocalDate.of(2026, 8, 15)));
        stub(expenses);

        List<SubscriptionResponse> found = service.detectSubscriptions(USER_ID);

        assertThat(found).hasSize(1);
        SubscriptionResponse sub = found.get(0);
        assertThat(sub.getMerchant()).isEqualTo("Netflix");
        assertThat(sub.getCadence()).isEqualTo(SubscriptionCadence.MONTHLY);
        assertThat(sub.getOccurrenceCount()).isEqualTo(3);
        assertThat(sub.isPriceIncreased()).isFalse();
        assertThat(sub.getTypicalAmount()).isEqualByComparingTo("499.00");
    }

    @Test
    void aPriceBumpOnTheLatestCharge_isFlaggedAsAPriceIncrease() {
        List<Expense> expenses = new ArrayList<>();
        expenses.add(expense("Netflix", new BigDecimal("499.00"), LocalDate.of(2026, 6, 15)));
        expenses.add(expense("Netflix", new BigDecimal("499.00"), LocalDate.of(2026, 7, 15)));
        expenses.add(expense("Netflix", new BigDecimal("649.00"), LocalDate.of(2026, 8, 15)));
        stub(expenses);

        List<SubscriptionResponse> found = service.detectSubscriptions(USER_ID);

        assertThat(found).hasSize(1);
        SubscriptionResponse sub = found.get(0);
        assertThat(sub.isPriceIncreased()).isTrue();
        assertThat(sub.getPreviousAmount()).isEqualByComparingTo("499.00");
        assertThat(sub.getCurrentAmount()).isEqualByComparingTo("649.00");
    }

    @Test
    void aSingleLargeOneOffExpense_isNotFlaggedAsASubscription() {
        List<Expense> expenses = new ArrayList<>();
        expenses.add(expense("Croma", new BigDecimal("45000.00"), LocalDate.of(2026, 8, 1)));
        stub(expenses);

        assertThat(service.detectSubscriptions(USER_ID)).isEmpty();
    }

    @Test
    void anIrregularMerchant_withNoConsistentCadenceOrAmount_isNotFlagged() {
        List<Expense> expenses = new ArrayList<>();
        expenses.add(expense("Amazon", new BigDecimal("799.00"), LocalDate.of(2026, 1, 3)));
        expenses.add(expense("Amazon", new BigDecimal("2450.00"), LocalDate.of(2026, 3, 19)));
        expenses.add(expense("Amazon", new BigDecimal("399.00"), LocalDate.of(2026, 4, 2)));
        expenses.add(expense("Amazon", new BigDecimal("5200.00"), LocalDate.of(2026, 8, 27)));
        stub(expenses);

        assertThat(service.detectSubscriptions(USER_ID)).isEmpty();
    }
}
