package com.marketai.card.optimizer;

import com.marketai.expense.entity.Expense;
import com.marketai.expense.entity.ExpenseCategory;
import com.marketai.expense.repository.ExpenseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * The coverage rule is the point of these tests: a card verdict built on spend we could not
 * categorize must announce that, not present a confident rupee figure.
 */
class SpendAggregatorTest {

    private ExpenseRepository repo;
    private SpendAggregator aggregator;

    @BeforeEach
    void setup() {
        repo = mock(ExpenseRepository.class);
        aggregator = new SpendAggregator(repo);
    }

    private Expense expense(String amount, ExpenseCategory cat) {
        Expense e = new Expense();
        e.setUserId(1L);
        e.setAmount(new BigDecimal(amount));
        e.setCategory(cat);
        e.setExpenseDate(LocalDate.now().minusDays(5));
        return e;
    }

    @Test
    void coverageIsOneWhenEverythingIsCategorized() {
        when(repo.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(anyLong(), any(), any()))
            .thenReturn(Arrays.asList(
                expense("1000", ExpenseCategory.FOOD),
                expense("2000", ExpenseCategory.TRAVEL)));

        SpendAggregator.SpendProfile p = aggregator.aggregate(1L);
        assertThat(p.getCoverage()).isEqualByComparingTo("1.0");
        assertThat(p.getObservedSpend()).isEqualByComparingTo("3000.00");
        assertThat(p.getByCategory()).hasSize(2);
    }

    @Test
    void uncategorizedSpendDragsCoverageDownAndIsFlagged() {
        when(repo.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(anyLong(), any(), any()))
            .thenReturn(Arrays.asList(
                expense("1000", ExpenseCategory.FOOD),
                expense("3000", ExpenseCategory.UNCATEGORIZED)));

        SpendAggregator.SpendProfile p = aggregator.aggregate(1L);
        assertThat(p.getCoverage()).isEqualByComparingTo("0.25");
        // Below 50% the note must say so — silently reporting a confident split would be the bug.
        assertThat(p.getNote()).contains("weakly grounded");
    }

    @Test
    void annualProjectionScalesTheWindowAndSaysSoIsNotSeasonallyAdjusted() {
        when(repo.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(anyLong(), any(), any()))
            .thenReturn(Collections.singletonList(expense("9000", ExpenseCategory.FOOD)));

        SpendAggregator.SpendProfile p = aggregator.aggregate(1L);
        // 9000 over 90 days at the 4dp factor (4.0556) -> 36500.40. The factor is rounded
        // deliberately so annualizationBasis reads cleanly; on an explicitly un-adjusted
        // projection that precision is immaterial.
        assertThat(p.getProjectedAnnualSpend()).isEqualByComparingTo("36500.40");
        assertThat(p.getAnnualizationBasis()).contains("not seasonally adjusted");
    }

    @Test
    void noSpendYieldsZeroCoverageAndAnExplicitNote() {
        when(repo.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(anyLong(), any(), any()))
            .thenReturn(Collections.emptyList());

        SpendAggregator.SpendProfile p = aggregator.aggregate(1L);
        assertThat(p.getCoverage()).isEqualByComparingTo("0");
        assertThat(p.getNote()).contains("cannot be personalised");
    }

    @Test
    void categoriesAreAlwaysMarkedInferredNotMcc() {
        when(repo.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(anyLong(), any(), any()))
            .thenReturn(Collections.singletonList(expense("500", ExpenseCategory.FOOD)));

        SpendAggregator.SpendProfile p = aggregator.aggregate(1L);
        assertThat(p.getByCategory().get(0).isInferred()).isTrue();
    }
}
