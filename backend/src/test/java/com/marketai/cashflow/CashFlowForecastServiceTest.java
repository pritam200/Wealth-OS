package com.marketai.cashflow;

import com.marketai.cashflow.dto.DailyProjection;
import com.marketai.cashflow.dto.ProjectedEvent;
import com.marketai.cashflow.service.CashFlowForecastService;
import com.marketai.expense.entity.Expense;
import com.marketai.expense.entity.ExpenseCategory;
import com.marketai.expense.repository.ExpenseRepository;
import com.marketai.income.entity.Income;
import com.marketai.income.entity.IncomeSource;
import com.marketai.income.repository.IncomeRepository;
import com.marketai.ledger.repository.CashAccountRepository;
import com.marketai.scheduled.entity.RecurringInvestment;
import com.marketai.scheduled.repository.RecurringInvestmentRepository;
import com.marketai.tracking.entity.FixedDeposit;
import com.marketai.tracking.entity.RecurringDeposit;
import com.marketai.tracking.repository.FixedDepositRepository;
import com.marketai.tracking.repository.RecurringDepositRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CashFlowForecastServiceTest {

    private static final Long USER = 9L;

    private CashAccountRepository cashAccounts;
    private IncomeRepository incomes;
    private ExpenseRepository expenses;
    private RecurringInvestmentRepository recurringInvestments;
    private FixedDepositRepository fixedDeposits;
    private RecurringDepositRepository recurringDeposits;
    private CashFlowForecastService service;

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    @BeforeEach
    void setUp() {
        cashAccounts = mock(CashAccountRepository.class);
        incomes = mock(IncomeRepository.class);
        expenses = mock(ExpenseRepository.class);
        recurringInvestments = mock(RecurringInvestmentRepository.class);
        fixedDeposits = mock(FixedDepositRepository.class);
        recurringDeposits = mock(RecurringDepositRepository.class);
        service = new CashFlowForecastService(
            cashAccounts, incomes, expenses, recurringInvestments, fixedDeposits, recurringDeposits);

        when(cashAccounts.sumBalanceByUser(USER)).thenReturn(bd("100000"));
        when(incomes.findByUserIdAndIncomeDateBetweenOrderByIncomeDateDesc(eq(USER), any(), any()))
            .thenReturn(List.of());
        when(expenses.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(eq(USER), any(), any()))
            .thenReturn(List.of());
        when(recurringInvestments.findByUserIdOrderByCreatedAtDesc(USER)).thenReturn(List.of());
        when(fixedDeposits.findByUserIdOrderByCreatedAtDesc(USER)).thenReturn(List.of());
        when(recurringDeposits.findByUserIdOrderByCreatedAtDesc(USER)).thenReturn(List.of());
    }

    private Income income(LocalDate date, String amount, IncomeSource source) {
        return Income.builder().userId(USER).description("test").amount(bd(amount))
            .source(source).incomeDate(date).build();
    }

    private Expense expense(LocalDate date, String amount, ExpenseCategory category) {
        return Expense.builder().userId(USER).description("test").amount(bd(amount))
            .category(category).expenseDate(date).build();
    }

    @Test
    void startsFromTodaysCashBalanceOnDayZero() {
        List<DailyProjection> forecast = service.forecast(USER, 5);

        assertThat(forecast).hasSize(6); // today + 5 days ahead
        assertThat(forecast.get(0).getDate()).isEqualTo(LocalDate.now());
        assertThat(forecast.get(0).getProjectedBalance()).isEqualByComparingTo("100000.00");
    }

    @Test
    void recurringIncomeAndExpenseHistoryProjectForward() {
        LocalDate today = LocalDate.now();
        // Three months of a ~15th-of-month salary credit, so it clears the recurring threshold.
        when(incomes.findByUserIdAndIncomeDateBetweenOrderByIncomeDateDesc(eq(USER), any(), any()))
            .thenReturn(List.of(
                income(today.minusMonths(1).withDayOfMonth(15), "60000", IncomeSource.SALARY),
                income(today.minusMonths(2).withDayOfMonth(15), "60000", IncomeSource.SALARY)));
        // A daily grocery spend, smoothed into a run-rate rather than pinned to one day.
        when(expenses.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(eq(USER), any(), any()))
            .thenReturn(List.of(
                expense(today.minusDays(10), "3000", ExpenseCategory.GROCERIES),
                expense(today.minusDays(40), "3000", ExpenseCategory.GROCERIES)));

        List<DailyProjection> forecast = service.forecast(USER, 45);

        // Every expense-run-rate day should be marked estimated, never presented as certain.
        boolean anyEstimatedExpense = forecast.stream()
            .flatMap(d -> d.getEvents().stream())
            .anyMatch(e -> "EXPENSE".equals(e.getType()) && e.isEstimated());
        assertThat(anyEstimatedExpense).isTrue();

        boolean anyEstimatedIncome = forecast.stream()
            .flatMap(d -> d.getEvents().stream())
            .anyMatch(e -> "INCOME".equals(e.getType()) && e.isEstimated());
        assertThat(anyEstimatedIncome).isTrue();

        // The recurring income must land on (about) the 15th, not spread evenly like expenses.
        DailyProjection incomeDay = forecast.stream()
            .filter(d -> d.getEvents().stream().anyMatch(e -> "INCOME".equals(e.getType())))
            .findFirst().orElseThrow();
        assertThat(incomeDay.getDate().getDayOfMonth()).isEqualTo(15);
    }

    @Test
    void sipDebitLandsOnItsScheduledDay() {
        LocalDate today = LocalDate.now();
        LocalDate anchor = today.plusDays(3);
        RecurringInvestment sip = RecurringInvestment.builder()
            .id(1L).type(RecurringInvestment.Type.SIP).label("HDFC Flexi Cap")
            .amount(bd("5000")).startDate(anchor).status("ACTIVE").build();
        when(recurringInvestments.findByUserIdOrderByCreatedAtDesc(USER)).thenReturn(List.of(sip));

        List<DailyProjection> forecast = service.forecast(USER, 10);

        DailyProjection day = forecast.stream().filter(d -> d.getDate().equals(anchor)).findFirst().orElseThrow();
        ProjectedEvent event = day.getEvents().stream().filter(e -> "SIP".equals(e.getType())).findFirst().orElseThrow();
        assertThat(event.getAmount()).isEqualByComparingTo("-5000");
        assertThat(event.isEstimated()).isFalse();

        // Balance on that day must be lower than the day before by exactly the SIP amount.
        BigDecimal before = forecast.get(forecast.indexOf(day) - 1).getProjectedBalance();
        assertThat(before.subtract(day.getProjectedBalance())).isEqualByComparingTo("5000.00");
    }

    @Test
    void fdMaturityCreditsTheExactMaturityDate() {
        LocalDate today = LocalDate.now();
        LocalDate maturity = today.plusDays(20);
        FixedDeposit fd = FixedDeposit.builder()
            .id(1L).bank("HDFC").principal(bd("100000")).rate(bd("7.0")).compounding("quarterly")
            .startDate(today.minusYears(1)).maturityDate(maturity).status("ACTIVE")
            .maturityAmount(bd("107500")).build();
        when(fixedDeposits.findByUserIdOrderByCreatedAtDesc(USER)).thenReturn(List.of(fd));

        List<DailyProjection> forecast = service.forecast(USER, 30);

        DailyProjection day = forecast.stream().filter(d -> d.getDate().equals(maturity)).findFirst().orElseThrow();
        ProjectedEvent event = day.getEvents().stream().filter(e -> "FD_MATURITY".equals(e.getType())).findFirst().orElseThrow();
        assertThat(event.getAmount()).isEqualByComparingTo("107500");
        assertThat(event.isEstimated()).isFalse();
    }

    @Test
    void rdMaturityCreditsTheDerivedMaturityDate() {
        LocalDate today = LocalDate.now();
        LocalDate expectedMaturity = today.plusDays(2);
        LocalDate start = expectedMaturity.minusMonths(12); // matures in 2 days at tenure=12
        RecurringDeposit rd = RecurringDeposit.builder()
            .id(1L).bank("SBI").monthlyAmount(bd("5000")).rate(bd("6.5"))
            .startDate(start).tenureMonths(12).status("ACTIVE").build();
        when(recurringDeposits.findByUserIdOrderByCreatedAtDesc(USER)).thenReturn(List.of(rd));

        List<DailyProjection> forecast = service.forecast(USER, 10);

        DailyProjection day = forecast.stream().filter(d -> d.getDate().equals(expectedMaturity)).findFirst().orElseThrow();
        ProjectedEvent event = day.getEvents().stream().filter(e -> "RD_MATURITY".equals(e.getType())).findFirst().orElseThrow();
        assertThat(event.getAmount()).isGreaterThan(bd("60000")); // > 12 * 5000 principal, interest included
        assertThat(event.isEstimated()).isFalse();
    }

    @Test
    void lowHistoryFallsBackToNoRecurringIncomeRatherThanGuessingFromOneDataPoint() {
        LocalDate today = LocalDate.now();
        // A single historical income row must not be treated as "recurring" — one data point
        // gives no evidence of a pattern.
        when(incomes.findByUserIdAndIncomeDateBetweenOrderByIncomeDateDesc(eq(USER), any(), any()))
            .thenReturn(List.of(income(today.minusMonths(1), "60000", IncomeSource.FREELANCE)));

        List<DailyProjection> forecast = service.forecast(USER, 30);

        boolean anyIncomeEvent = forecast.stream()
            .flatMap(d -> d.getEvents().stream())
            .anyMatch(e -> "INCOME".equals(e.getType()));
        assertThat(anyIncomeEvent).isFalse();
        // With no events at all, the balance simply stays flat at the starting cash figure.
        assertThat(forecast.get(forecast.size() - 1).getProjectedBalance()).isEqualByComparingTo("100000.00");
    }
}
