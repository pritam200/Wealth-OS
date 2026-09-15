package com.marketai.networth.attribution;

import com.marketai.expense.entity.Expense;
import com.marketai.expense.repository.ExpenseRepository;
import com.marketai.income.entity.Income;
import com.marketai.income.repository.IncomeRepository;
import com.marketai.networth.entity.NetWorthSnapshot;
import com.marketai.networth.repository.NetWorthSnapshotRepository;
import com.marketai.networth.service.NetWorthAttributionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NetWorthAttributionServiceTest {

    private static final Long USER = 4L;
    private static final LocalDate START = LocalDate.of(2026, 8, 1);
    private static final LocalDate END = LocalDate.of(2026, 8, 31);

    private NetWorthSnapshotRepository snapshots;
    private IncomeRepository incomes;
    private ExpenseRepository expenses;
    private NetWorthAttributionService service;

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    @BeforeEach
    void setUp() {
        snapshots = mock(NetWorthSnapshotRepository.class);
        incomes = mock(IncomeRepository.class);
        expenses = mock(ExpenseRepository.class);
        service = new NetWorthAttributionService(
            snapshots, incomes, expenses, new NetWorthAttributionCalculator());

        when(incomes.findByUserIdAndIncomeDateBetweenOrderByIncomeDateDesc(any(), any(), any()))
            .thenReturn(List.of());
        when(expenses.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(any(), any(), any()))
            .thenReturn(List.of());
    }

    private void givenSnapshots(String opening, String closing) {
        when(snapshots.findByUserIdOrderBySnapshotDateAsc(USER)).thenReturn(List.of(
            NetWorthSnapshot.builder().userId(USER).snapshotDate(START)
                .netWorth(bd(opening)).totalAssets(bd(opening)).build(),
            NetWorthSnapshot.builder().userId(USER).snapshotDate(END)
                .netWorth(bd(closing)).totalAssets(bd(closing)).build()));
    }

    private void givenIncome(String amount) {
        Income i = new Income();
        i.setAmount(bd(amount));
        when(incomes.findByUserIdAndIncomeDateBetweenOrderByIncomeDateDesc(eq(USER), any(), any()))
            .thenReturn(List.of(i));
    }

    private void givenExpense(String amount) {
        Expense e = new Expense();
        e.setAmount(bd(amount));
        when(expenses.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(eq(USER), any(), any()))
            .thenReturn(List.of(e));
    }

    @Test
    @DisplayName("the change is split into what you did and what the market did")
    void decomposesTheChange() {
        givenSnapshots("1000000", "1085000");
        givenIncome("120000");
        givenExpense("55000");

        AttributionResult r = service.attribute(USER, START, END).orElseThrow();

        assertThat(r.change()).isEqualByComparingTo("85000.00");
        assertThat(r.byKind().get(AttributionKind.INCOME)).isEqualByComparingTo("120000");
        assertThat(r.byKind().get(AttributionKind.EXPENSE)).isEqualByComparingTo("-55000");
        // 85,000 change − (120,000 earned − 55,000 spent) = 20,000 from the market.
        assertThat(r.revaluation()).isEqualByComparingTo("20000");
        assertThat(r.closes()).isTrue();
        assertThat(r.summary()).contains("₹20000.00 from market movement");
    }

    @Test
    @DisplayName("a fall in net worth is described as a fall, not a negative rise")
    void handlesADecline() {
        givenSnapshots("1000000", "950000");
        givenExpense("30000");

        AttributionResult r = service.attribute(USER, START, END).orElseThrow();

        assertThat(r.change()).isEqualByComparingTo("-50000.00");
        assertThat(r.revaluation()).isEqualByComparingTo("-20000");
        assertThat(r.summary()).contains("fell by ₹50000.00");
    }

    @Test
    @DisplayName("a single snapshot returns nothing rather than treating zero as the opening")
    void insufficientHistoryReturnsEmpty() {
        // Defaulting the opening to zero would report a first-ever snapshot as though the user
        // had earned their entire net worth in one period.
        when(snapshots.findByUserIdOrderBySnapshotDateAsc(USER)).thenReturn(List.of(
            NetWorthSnapshot.builder().userId(USER).snapshotDate(END).netWorth(bd("500000")).build()));

        assertThat(service.attribute(USER, START, END)).isEmpty();
    }

    @Test
    void noHistoryAtAllReturnsEmpty() {
        when(snapshots.findByUserIdOrderBySnapshotDateAsc(USER)).thenReturn(List.of());

        assertThat(service.attribute(USER, START, END)).isEmpty();
        assertThat(service.attributeRecent(USER)).isEmpty();
    }

    @Test
    @DisplayName("with no income or expense, the whole change is market movement")
    void allMarketMovement() {
        givenSnapshots("1000000", "1042000");

        AttributionResult r = service.attribute(USER, START, END).orElseThrow();

        assertThat(r.revaluation()).isEqualByComparingTo("42000");
        assertThat(r.behavioural()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("the components always reconcile to the change, by construction")
    void componentsAlwaysClose() {
        givenSnapshots("750000", "700000");
        givenIncome("80000");
        givenExpense("140000");

        AttributionResult r = service.attribute(USER, START, END).orElseThrow();

        assertThat(r.unexplained()).isEqualByComparingTo("0.00");
        assertThat(r.closes()).isTrue();
    }

    @Test
    void snapshotsWithNullNetWorthAreIgnored() {
        when(snapshots.findByUserIdOrderBySnapshotDateAsc(USER)).thenReturn(List.of(
            NetWorthSnapshot.builder().userId(USER).snapshotDate(START).netWorth(null).build(),
            NetWorthSnapshot.builder().userId(USER).snapshotDate(END).netWorth(bd("100000")).build()));

        // Only one usable point, so there is nothing to compare.
        assertThat(service.attribute(USER, START, END)).isEmpty();
    }
}
