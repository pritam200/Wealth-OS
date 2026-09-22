package com.marketai.planner.service;

import com.marketai.expense.entity.Expense;
import com.marketai.expense.repository.ExpenseRepository;
import com.marketai.planner.dto.PlannerDtos.*;
import com.marketai.planner.entity.PlanCategory;
import com.marketai.planner.entity.PlannerSettings;
import com.marketai.planner.repository.MonthlyReflectionRepository;
import com.marketai.planner.repository.PlanCategoryRepository;
import com.marketai.planner.repository.PlannerSettingsRepository;
import com.marketai.planner.repository.SinkingFundEntryRepository;
import com.marketai.planner.repository.SinkingFundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Core plan-vs-actual aggregation: classified/overridden expenses bucket into the right
 * category, moving a transaction changes only the two affected boxes (never the household
 * total), and the four-state status line matches the thresholds documented in PlannerService.
 */
class PlannerServiceTest {

    private static final Long USER = 1L;

    private PlanCategoryRepository categoryRepository;
    private PlannerSettingsRepository settingsRepository;
    private ExpenseRepository expenseRepository;
    private SinkingFundEntryRepository sinkingFundEntryRepository;
    private SinkingFundRepository sinkingFundRepository;
    private MonthlyReflectionRepository reflectionRepository;
    private PlannerService service;

    @BeforeEach
    void setUp() {
        categoryRepository = mock(PlanCategoryRepository.class);
        settingsRepository = mock(PlannerSettingsRepository.class);
        expenseRepository = mock(ExpenseRepository.class);
        sinkingFundEntryRepository = mock(SinkingFundEntryRepository.class);
        sinkingFundRepository = mock(SinkingFundRepository.class);
        reflectionRepository = mock(MonthlyReflectionRepository.class);

        PlanCategoryClassifier classifier = new PlanCategoryClassifier(categoryRepository);
        service = new PlannerService(categoryRepository, settingsRepository, expenseRepository,
            classifier, sinkingFundEntryRepository, sinkingFundRepository, reflectionRepository);

        when(categoryRepository.existsByUserId(USER)).thenReturn(true);
        when(settingsRepository.findByUserId(USER))
            .thenReturn(Optional.of(PlannerSettings.builder().userId(USER).monthlyLimit(new BigDecimal("65000.00")).build()));
        when(sinkingFundRepository.findByUserIdAndActiveTrueOrderBySortOrderAsc(USER)).thenReturn(List.of());
    }

    private PlanCategory groceries() {
        return PlanCategory.builder().userId(USER).key("GROCERIES").name("Groceries").groupName("Food & Household")
            .plannedAmount(new BigDecimal("6500.00")).keywords("bigbasket,dmart,grocery").active(true).build();
    }

    private PlanCategory misc() {
        return PlanCategory.builder().userId(USER).key("MISCELLANEOUS").name("Miscellaneous").groupName("Health & Maintenance/Misc")
            .plannedAmount(new BigDecimal("1100.00")).fallback(true).active(true).build();
    }

    private Expense expense(String merchant, BigDecimal amount, LocalDate date) {
        return Expense.builder().id(1L).userId(USER).description(merchant).merchant(merchant)
            .amount(amount).expenseDate(date).category(com.marketai.expense.entity.ExpenseCategory.FOOD).build();
    }

    @Test
    @DisplayName("a classified expense's amount lands in the matching category's actual, not Miscellaneous")
    void classifiedExpenseBucketsCorrectly() {
        when(categoryRepository.findByUserIdAndActiveTrueOrderBySortOrderAsc(USER)).thenReturn(List.of(groceries(), misc()));
        when(expenseRepository.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(eq(USER), any(), any()))
            .thenReturn(List.of(expense("DMart", new BigDecimal("850.00"), LocalDate.of(2026, 9, 10))));

        MonthlyPlanResponse plan = service.getMonthlyPlan(USER, 2026, 9);

        CategoryPlanLine groceriesLine = plan.getCategories().stream().filter(c -> c.getKey().equals("GROCERIES")).findFirst().orElseThrow();
        CategoryPlanLine miscLine = plan.getCategories().stream().filter(c -> c.getKey().equals("MISCELLANEOUS")).findFirst().orElseThrow();

        assertThat(groceriesLine.getActual()).isEqualByComparingTo("850.00");
        assertThat(miscLine.getActual()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("an unmatched merchant falls back to Miscellaneous rather than being dropped")
    void unmatchedExpenseFallsBackToMiscellaneous() {
        when(categoryRepository.findByUserIdAndActiveTrueOrderBySortOrderAsc(USER)).thenReturn(List.of(groceries(), misc()));
        when(expenseRepository.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(eq(USER), any(), any()))
            .thenReturn(List.of(expense("Some Random Shop", new BigDecimal("300.00"), LocalDate.of(2026, 9, 10))));

        MonthlyPlanResponse plan = service.getMonthlyPlan(USER, 2026, 9);
        CategoryPlanLine miscLine = plan.getCategories().stream().filter(c -> c.getKey().equals("MISCELLANEOUS")).findFirst().orElseThrow();
        assertThat(miscLine.getActual()).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("a user override moves the expense's amount to the new category without changing the household total")
    void overrideMovesAmountBetweenCategories() {
        when(categoryRepository.findByUserIdAndActiveTrueOrderBySortOrderAsc(USER)).thenReturn(List.of(groceries(), misc()));
        Expense e = expense("Some Random Shop", new BigDecimal("300.00"), LocalDate.of(2026, 9, 10));
        e.setPlanCategoryOverride("GROCERIES"); // user moved it from the classifier's Miscellaneous guess
        when(expenseRepository.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(eq(USER), any(), any()))
            .thenReturn(List.of(e));

        MonthlyPlanResponse plan = service.getMonthlyPlan(USER, 2026, 9);
        CategoryPlanLine groceriesLine = plan.getCategories().stream().filter(c -> c.getKey().equals("GROCERIES")).findFirst().orElseThrow();
        CategoryPlanLine miscLine = plan.getCategories().stream().filter(c -> c.getKey().equals("MISCELLANEOUS")).findFirst().orElseThrow();

        assertThat(groceriesLine.getActual()).isEqualByComparingTo("300.00");
        assertThat(miscLine.getActual()).isEqualByComparingTo("0.00");
        assertThat(plan.getActualTotal()).isEqualByComparingTo("300.00"); // unchanged household total
    }

    @Test
    @DisplayName("status is WITHIN_PLAN when actual is below planned")
    void statusWithinPlan() {
        when(categoryRepository.findByUserIdAndActiveTrueOrderBySortOrderAsc(USER)).thenReturn(List.of(groceries()));
        when(expenseRepository.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(eq(USER), any(), any()))
            .thenReturn(List.of(expense("DMart", new BigDecimal("1000.00"), LocalDate.of(2026, 9, 10))));

        assertThat(service.getMonthlyPlan(USER, 2026, 9).getStatus()).isEqualTo("WITHIN_PLAN");
    }

    @Test
    @DisplayName("status is OVER_LIMIT when actual exceeds the absolute monthly limit")
    void statusOverLimit() {
        PlanCategory bigCategory = PlanCategory.builder().userId(USER).key("RENT").name("Rent").groupName("Housing & Bills")
            .plannedAmount(new BigDecimal("31000.00")).keywords("rent").active(true).build();
        when(categoryRepository.findByUserIdAndActiveTrueOrderBySortOrderAsc(USER)).thenReturn(List.of(bigCategory));
        when(expenseRepository.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(eq(USER), any(), any()))
            .thenReturn(List.of(expense("Rent payment", new BigDecimal("70000.00"), LocalDate.of(2026, 9, 1))));

        assertThat(service.getMonthlyPlan(USER, 2026, 9).getStatus()).isEqualTo("OVER_LIMIT");
    }

    @Test
    @DisplayName("group rollup sums planned/actual across every category sharing a group name")
    void groupRollupSumsAcrossCategories() {
        PlanCategory meat = PlanCategory.builder().userId(USER).key("MEAT_NON_VEG").name("Meat / Non-Veg").groupName("Food & Household")
            .plannedAmount(new BigDecimal("2000.00")).keywords("chicken,mutton").active(true).build();
        when(categoryRepository.findByUserIdAndActiveTrueOrderBySortOrderAsc(USER)).thenReturn(List.of(groceries(), meat));
        when(expenseRepository.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(eq(USER), any(), any()))
            .thenReturn(List.of(
                expense("DMart", new BigDecimal("500.00"), LocalDate.of(2026, 9, 5)),
                expense("Local chicken shop", new BigDecimal("400.00"), LocalDate.of(2026, 9, 6))));

        MonthlyPlanResponse plan = service.getMonthlyPlan(USER, 2026, 9);
        GroupSummary foodHousehold = plan.getGroupSummaries().stream()
            .filter(g -> g.getGroupName().equals("Food & Household")).findFirst().orElseThrow();

        assertThat(foodHousehold.getPlanned()).isEqualByComparingTo("8500.00"); // 6500 + 2000
        assertThat(foodHousehold.getActual()).isEqualByComparingTo("900.00");   // 500 + 400
    }
}
