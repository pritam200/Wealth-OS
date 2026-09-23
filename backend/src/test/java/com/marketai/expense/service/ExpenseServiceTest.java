package com.marketai.expense.service;

import com.marketai.expense.dto.ExpenseRequest;
import com.marketai.expense.dto.ExpenseResponse;
import com.marketai.expense.entity.Expense;
import com.marketai.expense.entity.ExpenseCategory;
import com.marketai.expense.repository.ExpenseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExpenseServiceTest {

    private ExpenseRepository expenseRepository;
    private ExpenseService service;

    @BeforeEach
    void setUp() {
        expenseRepository = mock(ExpenseRepository.class);
        service = new ExpenseService(expenseRepository);
        when(expenseRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(expenseRepository.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(any(), any(), any()))
                .thenReturn(Collections.emptyList());
    }

    @Test
    void explicitMerchantOverridesAutoDerivation() {
        ExpenseRequest req = new ExpenseRequest();
        req.setDescription("SWIGGY*BLR ORDER 123");
        req.setAmount(new BigDecimal("450"));
        req.setCategory("Food");
        req.setExpenseDate(LocalDate.of(2026, 9, 20));
        req.setMerchant("Swiggy");

        ExpenseResponse resp = service.addExpense(1L, req);

        assertThat(resp.getMerchant()).isEqualTo("Swiggy");
    }

    @Test
    void blankMerchantFallsBackToAutoDerivation() {
        ExpenseRequest req = new ExpenseRequest();
        req.setDescription("SWIGGY*BLR ORDER 123");
        req.setAmount(new BigDecimal("450"));
        req.setCategory("Food");
        req.setExpenseDate(LocalDate.of(2026, 9, 20));
        req.setMerchant("");

        ExpenseResponse resp = service.addExpense(1L, req);

        assertThat(resp.getMerchant()).isNotEqualTo("");
    }

    @Test
    void addExpensePersistsPaymentMethodAndCashAccount() {
        ExpenseRequest req = new ExpenseRequest();
        req.setDescription("Groceries");
        req.setAmount(new BigDecimal("2000"));
        req.setCategory("Food");
        req.setExpenseDate(LocalDate.of(2026, 9, 20));
        req.setPaymentMethod("UPI");
        req.setCashAccountId(42L);

        ExpenseResponse resp = service.addExpense(1L, req);

        assertThat(resp.getPaymentMethod()).isEqualTo("UPI");
        assertThat(resp.getCashAccountId()).isEqualTo(42L);
    }

    @Test
    void updateExpenseRoundTripsPaymentMethodAndCashAccount() {
        Expense existing = Expense.builder()
                .id(7L).userId(1L).description("Old").amount(new BigDecimal("100"))
                .category(ExpenseCategory.UNCATEGORIZED).expenseDate(LocalDate.of(2026, 9, 1))
                .build();
        when(expenseRepository.findById(7L)).thenReturn(Optional.of(existing));

        ExpenseRequest req = new ExpenseRequest();
        req.setPaymentMethod("Credit Card");
        req.setCashAccountId(99L);

        ExpenseResponse resp = service.updateExpense(1L, 7L, req);

        assertThat(resp.getPaymentMethod()).isEqualTo("Credit Card");
        assertThat(resp.getCashAccountId()).isEqualTo(99L);
    }
}
