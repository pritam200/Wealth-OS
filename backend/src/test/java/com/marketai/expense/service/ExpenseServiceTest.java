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
    private com.marketai.card.repository.CardStatementLineRepository cardLines;
    private com.marketai.expense.repository.MerchantCategoryRuleRepository ruleRepo;

    @BeforeEach
    void setUp() {
        expenseRepository = mock(ExpenseRepository.class);
        cardLines = mock(com.marketai.card.repository.CardStatementLineRepository.class);
        ruleRepo = mock(com.marketai.expense.repository.MerchantCategoryRuleRepository.class);
        service = new ExpenseService(expenseRepository, cardLines, new MerchantCategoryRuleService(ruleRepo, expenseRepository));
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

    private static ExpenseRequest coffee() {
        ExpenseRequest req = new ExpenseRequest();
        req.setDescription("Coffee");
        req.setMerchant("Blue Tokai");
        req.setAmount(new BigDecimal("250"));
        req.setExpenseDate(LocalDate.of(2026, 9, 20));
        return req;
    }

    @Test
    void enteringByHandWhatTheEmailAlreadyRecordedAddsNothingAndSaysSo() {
        Expense fromEmail = Expense.builder().id(5L).userId(1L).description("BLUE TOKAI COFFEE")
                .merchant("BLUE TOKAI COFFEE ROASTERS").amount(new BigDecimal("250.00"))
                .category(ExpenseCategory.FOOD).expenseDate(LocalDate.of(2026, 9, 20)).sourceEmailId("msg-1").build();
        when(expenseRepository.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(any(), any(), any()))
                .thenReturn(java.util.List.of(fromEmail));

        ExpenseResponse resp = service.addExpense(1L, coffee());

        assertThat(resp.isAlreadyRecorded()).isTrue();
        assertThat(resp.getId()).isEqualTo(5L);
        verify(expenseRepository, never()).save(any());
    }

    @Test
    void theUserCanStillAddItWhenItReallyIsASecondPayment() {
        Expense fromEmail = Expense.builder().id(5L).userId(1L).description("Blue Tokai").merchant("Blue Tokai")
                .amount(new BigDecimal("250")).category(ExpenseCategory.FOOD)
                .expenseDate(LocalDate.of(2026, 9, 20)).sourceEmailId("msg-1").build();
        when(expenseRepository.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(any(), any(), any()))
                .thenReturn(java.util.List.of(fromEmail));
        ExpenseRequest req = coffee();
        req.setConfirmSeparate(true);

        ExpenseResponse resp = service.addExpense(1L, req);

        assertThat(resp.isAlreadyRecorded()).isFalse();
        verify(expenseRepository).save(any());
    }

    @Test
    void twoHandEnteredCoffeesOnOneDayAreBothKept() {
        Expense first = Expense.builder().id(6L).userId(1L).description("Coffee").merchant("Blue Tokai")
                .amount(new BigDecimal("250")).category(ExpenseCategory.FOOD).expenseDate(LocalDate.of(2026, 9, 20)).build();
        when(expenseRepository.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(any(), any(), any()))
                .thenReturn(java.util.List.of(first));

        ExpenseResponse resp = service.addExpense(1L, coffee());

        assertThat(resp.isAlreadyRecorded()).isFalse();
        verify(expenseRepository).save(any());
    }

    @Test
    void editingARefundKeepsItNegative() {
        Expense refund = Expense.builder().id(5L).userId(1L).description("Refund").amount(new BigDecimal("-300"))
                .refundOfExpenseId(4L).category(ExpenseCategory.SHOPPING).expenseDate(LocalDate.of(2026, 9, 2)).build();
        when(expenseRepository.findById(5L)).thenReturn(Optional.of(refund));
        ExpenseRequest req = new ExpenseRequest();
        req.setDescription("Refund");
        req.setAmount(new BigDecimal("350"));

        ExpenseResponse res = service.updateExpense(1L, 5L, req);

        assertThat(res.getAmount()).isEqualByComparingTo("-350");
        assertThat(res.getRefundOfExpenseId()).isEqualTo(4L);
    }

    @Test
    void deletingAPurchaseTakesItsRefundsAndFreesItsCardLine() {
        Expense purchase = Expense.builder().id(4L).userId(1L).amount(new BigDecimal("300")).build();
        Expense refund = Expense.builder().id(5L).userId(1L).amount(new BigDecimal("-300")).refundOfExpenseId(4L).build();
        var line = com.marketai.card.entity.CardStatementLine.builder().id(9L).userId(1L).matchedExpenseId(4L)
                .status(com.marketai.card.entity.CardStatementLine.Status.MATCHED).build();
        when(expenseRepository.existsByIdAndUserId(4L, 1L)).thenReturn(true);
        when(expenseRepository.findById(4L)).thenReturn(Optional.of(purchase));
        when(expenseRepository.findByRefundOfExpenseId(4L)).thenReturn(java.util.List.of(refund));
        when(cardLines.findByUserIdAndMatchedExpenseId(1L, 4L)).thenReturn(java.util.List.of(line));
        when(cardLines.findByUserIdAndMatchedExpenseId(1L, 5L)).thenReturn(java.util.List.of());

        service.deleteExpense(1L, 4L);

        verify(expenseRepository).deleteAll(java.util.List.of(refund));
        verify(expenseRepository).deleteById(4L);
        assertThat(line.getMatchedExpenseId()).isNull();
        assertThat(line.getStatus()).isEqualTo(com.marketai.card.entity.CardStatementLine.Status.MISSING);
    }

    @Test
    void aCategoryRememberedForAMerchantMovesItsOtherExpensesAndIsUsedForNewOnes() {
        Expense edited = Expense.builder().id(1L).userId(1L).description("x").merchant("SWIGGY*BLR")
                .amount(new BigDecimal("200")).category(ExpenseCategory.UNCATEGORIZED).expenseDate(LocalDate.of(2026, 9, 1)).build();
        Expense older = Expense.builder().id(2L).userId(1L).merchant("Swiggy").amount(new BigDecimal("150"))
                .category(ExpenseCategory.UPI).expenseDate(LocalDate.of(2026, 8, 1)).build();
        Expense other = Expense.builder().id(3L).userId(1L).merchant("Zomato").amount(new BigDecimal("150"))
                .category(ExpenseCategory.UPI).expenseDate(LocalDate.of(2026, 8, 1)).build();
        when(expenseRepository.findById(1L)).thenReturn(Optional.of(edited));
        when(expenseRepository.findByUserIdOrderByExpenseDateDesc(1L)).thenReturn(java.util.List.of(edited, older, other));
        when(ruleRepo.findByUserIdAndMerchantKey(1L, "swiggyblr")).thenReturn(Optional.empty());

        ExpenseRequest req = new ExpenseRequest();
        req.setDescription("x");
        req.setAmount(new BigDecimal("200"));
        req.setCategory("Food Delivery");
        req.setMerchant("SWIGGY*BLR");
        req.setRememberForMerchant(true);
        ExpenseResponse res = service.updateExpense(1L, 1L, req);

        // "swiggyblr" is not "swiggy": only the same normalised merchant moves.
        assertThat(res.getAlsoRecategorised()).isZero();
        verify(ruleRepo).save(argThat(r -> r.getCategory() == ExpenseCategory.FOOD_DELIVERY && r.getMerchantKey().equals("swiggyblr")));

        when(ruleRepo.findByUserIdAndMerchantKey(1L, "swiggyblr")).thenReturn(Optional.of(
                com.marketai.expense.entity.MerchantCategoryRule.builder().userId(1L).merchantKey("swiggyblr")
                        .category(ExpenseCategory.FOOD_DELIVERY).build()));
        ExpenseRequest add = new ExpenseRequest();
        add.setDescription("order");
        add.setAmount(new BigDecimal("90"));
        add.setMerchant("SWIGGY*BLR");
        assertThat(service.addExpense(1L, add).getCategory()).isEqualTo("Food Delivery");
    }
}
