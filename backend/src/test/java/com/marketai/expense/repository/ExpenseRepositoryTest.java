package com.marketai.expense.repository;

import com.marketai.expense.entity.Expense;
import com.marketai.expense.entity.ExpenseCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the JPQL, including the fully-qualified enum literal added to exclude
 * ACCOUNT_TRANSFER from spend totals — a typo there compiles fine and only fails at boot,
 * which is why this needs a real persistence context rather than a mocked repository.
 */
@DataJpaTest
class ExpenseRepositoryTest {

    @Autowired
    private ExpenseRepository repo;

    private Expense saved(BigDecimal amount, ExpenseCategory category, LocalDate date) {
        return repo.saveAndFlush(Expense.builder()
            .userId(1L).description("test").amount(amount).category(category)
            .expenseDate(date).note("test").build());
    }

    @Test
    @DisplayName("sumByUserIdAndDateRange excludes ACCOUNT_TRANSFER so a CRED/CC-bill payment doesn't double-count spend")
    void sumExcludesAccountTransfer() {
        LocalDate day = LocalDate.of(2026, 9, 1);
        saved(new BigDecimal("500"), ExpenseCategory.FOOD_DELIVERY, day);
        saved(new BigDecimal("2000"), ExpenseCategory.ACCOUNT_TRANSFER, day);

        BigDecimal total = repo.sumByUserIdAndDateRange(1L, day, day);

        assertThat(total).isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("sumByCategory still reports ACCOUNT_TRANSFER on its own line, just not in the spend total")
    void sumByCategoryStillIncludesAccountTransfer() {
        LocalDate day = LocalDate.of(2026, 9, 2);
        saved(new BigDecimal("500"), ExpenseCategory.GROCERIES, day);
        saved(new BigDecimal("2000"), ExpenseCategory.ACCOUNT_TRANSFER, day);

        var rows = repo.sumByCategory(1L, day, day);

        assertThat(rows).hasSize(2);
    }
}
