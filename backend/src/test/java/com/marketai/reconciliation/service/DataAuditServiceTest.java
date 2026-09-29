package com.marketai.reconciliation.service;

import com.marketai.expense.entity.Expense;
import com.marketai.expense.entity.ExpenseCategory;
import com.marketai.income.entity.Income;
import com.marketai.income.entity.IncomeSource;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Transaction;
import com.marketai.reconciliation.service.DataAuditService.Classification;
import com.marketai.reconciliation.service.DataAuditService.Finding;
import com.marketai.tracking.entity.FixedDeposit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class DataAuditServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);

    private static Expense exp(long id, String email, String amount, LocalDate d, String merchant) {
        return Expense.builder().id(id).userId(1L).sourceEmailId(email).amount(new BigDecimal(amount))
            .expenseDate(d).merchant(merchant).description(merchant).category(ExpenseCategory.SHOPPING).build();
    }

    private static Map<Long, Classification> byId(List<Finding> f) {
        return f.stream().collect(Collectors.toMap(Finding::getId, Finding::getClassification));
    }

    @Test
    @DisplayName("the cross-day re-import is a duplicate of the first copy; same-day repeat lines are not")
    void expenseDuplicates() {
        LocalDate d = LocalDate.of(2026, 9, 1);
        List<Finding> f = DataAuditService.auditExpenses(List.of(
            exp(18, "m1", "870", d, "Swiggy"),
            exp(19, "m1", "870", d.plusDays(3), "Swiggy"),      // the bug: same email, later day
            exp(20, "m2", "250", d, "Cafe"),
            exp(21, "m2", "250", d, "Cafe"),                     // two identical lines in one statement
            exp(22, "m3", "250", d, "Cafe"),                     // another email, same day/amount/payee
            exp(23, "s1", "250", d, "Starbucks"),                // a card statement: the same coffee on
            exp(24, "s1", "250", d.plusDays(7), "Starbucks"),    //   two days is two lines
            exp(25, "u1", "99", d, "Netflix"),                   // no line count recorded for this email
            exp(26, "u1", "99", d.plusDays(2), "Netflix")
        ), Set.of(), Set.of(), lines(Map.of("m1", 2, "m2", 2, "m3", 1, "s1", 2, "u1", 2), Map.of("m1", 1, "m2", 2, "m3", 1, "s1", 2)), TODAY);

        Map<Long, Classification> c = byId(f);
        assertThat(c.get(18L)).isEqualTo(Classification.VERIFIED);
        assertThat(c.get(19L)).isEqualTo(Classification.DUPLICATE);
        assertThat(f.stream().filter(x -> x.getId() == 19L).findFirst().orElseThrow().getDuplicateOf()).isEqualTo(18L);
        assertThat(c.get(20L)).isEqualTo(Classification.VERIFIED);
        assertThat(c.get(21L)).isEqualTo(Classification.VERIFIED);
        assertThat(c.get(22L)).isEqualTo(Classification.REQUIRES_RECONCILIATION);
        // Two rows, two lines: both real.
        assertThat(c.get(23L)).isEqualTo(Classification.VERIFIED);
        assertThat(c.get(24L)).isEqualTo(Classification.VERIFIED);
        // Can't tell: never offered for removal.
        assertThat(c.get(26L)).isEqualTo(Classification.REQUIRES_RECONCILIATION);
    }

    private static DataAuditService.EmailLines lines(Map<String, Integer> rows, Map<String, Integer> extracted) {
        return new DataAuditService.EmailLines(rows, extracted);
    }

    @Test
    @DisplayName("broken rows are corrupted; a conflicted import is a conflict; an orphan refund is missing its purchase")
    void expenseIntegrity() {
        Expense conflicted = exp(3, "m9", "100", LocalDate.of(2026, 9, 2), "Amazon");
        conflicted.setSourceFingerprint("fp-x");
        Expense orphanRefund = exp(4, "m10", "-100", LocalDate.of(2026, 9, 3), "Amazon");
        orphanRefund.setRefundOfExpenseId(999L);
        List<Finding> f = DataAuditService.auditExpenses(List.of(
            exp(1, null, "0", LocalDate.of(2026, 9, 1), "X"),
            exp(2, null, "50", TODAY.plusDays(10), "Y"),
            conflicted, orphanRefund,
            exp(5, null, "-40", LocalDate.of(2026, 9, 4), "Z")
        ), Set.of("fp-x"), Set.of(), DataAuditService.EmailLines.UNKNOWN, TODAY);

        Map<Long, Classification> c = byId(f);
        assertThat(c.get(1L)).isEqualTo(Classification.CORRUPTED);
        assertThat(c.get(2L)).isEqualTo(Classification.CORRUPTED);
        assertThat(c.get(3L)).isEqualTo(Classification.CONFLICT);
        assertThat(c.get(4L)).isEqualTo(Classification.MISSING);
        assertThat(c.get(5L)).isEqualTo(Classification.CORRUPTED);
    }

    @Test
    @DisplayName("unmatched-sale proceeds and legacy capital-gain rows need reconciliation")
    void incomes() {
        List<Finding> f = DataAuditService.auditIncomes(List.of(
            Income.builder().id(1L).amount(new BigDecimal("15")).incomeDate(LocalDate.of(2026, 8, 1)).sourceEmailId("d1").source(IncomeSource.DIVIDEND).description("Dividend").build(),
            Income.builder().id(2L).amount(new BigDecimal("15")).incomeDate(LocalDate.of(2026, 8, 5)).sourceEmailId("d1").source(IncomeSource.DIVIDEND).description("Dividend").build(),
            Income.builder().id(3L).amount(new BigDecimal("150000")).incomeDate(LocalDate.of(2026, 8, 1)).source(IncomeSource.UNMATCHED_SALE).description("Sale").build(),
            Income.builder().id(4L).amount(new BigDecimal("900")).incomeDate(LocalDate.of(2026, 8, 1)).source(IncomeSource.CAPITAL_GAIN).description("Gain").build(),
            // One bank statement: salary on the 1st and a transfer-in of the same amount on the 20th.
            Income.builder().id(5L).amount(new BigDecimal("50000")).incomeDate(LocalDate.of(2026, 8, 1)).sourceEmailId("b1").source(IncomeSource.SALARY).payer("Acme Ltd").description("Salary").build(),
            Income.builder().id(6L).amount(new BigDecimal("50000")).incomeDate(LocalDate.of(2026, 8, 20)).sourceEmailId("b1").source(IncomeSource.OTHER).payer("R Sharma").description("Transfer in").build()
        ), Set.of(), Set.of(), lines(Map.of("d1", 2, "b1", 2), Map.of("d1", 1, "b1", 1)), TODAY);
        Map<Long, Classification> c = byId(f);
        assertThat(c.get(2L)).isEqualTo(Classification.DUPLICATE);
        assertThat(c.get(3L)).isEqualTo(Classification.REQUIRES_RECONCILIATION);
        assertThat(c.get(4L)).isEqualTo(Classification.REQUIRES_RECONCILIATION);
        // Different payer and kind: not the same credit, even with the line count off.
        assertThat(c.get(6L)).isEqualTo(Classification.VERIFIED);
    }

    private static Transaction t(long id, Transaction.TransactionType type, String date, String qty, String price, String email) {
        return Transaction.builder().id(id).type(type).transactionDate(LocalDate.parse(date))
            .quantity(new BigDecimal(qty)).price(new BigDecimal(price))
            .provenance(email == null ? null : com.marketai.common.ledger.Provenance.builder().sourceEmailId(email).build()).build();
    }

    @Test
    @DisplayName("ledger: a repeated trade reference is a duplicate, the same trade from two emails needs a look, an oversell is a missing purchase, drifted holding figures are corrupted")
    void holdings() {
        Holding h = Holding.builder().id(7L).symbol("TCS.NS").quantity(new BigDecimal("20")).averageCost(new BigDecimal("100")).build();
        List<Finding> f = DataAuditService.auditHolding(h, List.of(
            t(1, Transaction.TransactionType.BUY, "2026-01-05", "10", "100", "contract-note"),
            t(2, Transaction.TransactionType.BUY, "2026-01-05", "10", "100", "confirmation"),
            t(3, Transaction.TransactionType.SELL, "2026-03-01", "25", "120", "sell-note"),
            t(4, Transaction.TransactionType.BUY, "2026-04-02", "5", "110", "note-a"),
            t(5, Transaction.TransactionType.BUY, "2026-04-02", "5", "110", "note-b")
        ), Set.of(), TODAY);

        Map<String, Classification> c = f.stream().collect(Collectors.toMap(x -> x.getEntity() + ":" + x.getId(), Finding::getClassification));
        assertThat(c.get("TRANSACTION:1")).isEqualTo(Classification.VERIFIED);
        assertThat(c.get("TRANSACTION:2")).isEqualTo(Classification.REQUIRES_RECONCILIATION);
        assertThat(c.get("TRANSACTION:5")).isEqualTo(Classification.REQUIRES_RECONCILIATION);
        assertThat(c.get("TRANSACTION:3")).isEqualTo(Classification.MISSING);
        // The ledger (duplicate included) nets to 0 units; the holding says 20.
        assertThat(c.get("HOLDING:7")).isEqualTo(Classification.CORRUPTED);
    }

    @Test
    @DisplayName("a deposit long past maturity with nothing recorded needs reconciliation; one closed without a payout is missing it")
    void deposits() {
        FixedDeposit lapsed = FixedDeposit.builder().id(1L).bank("SBI").principal(new BigDecimal("100000")).rate(new BigDecimal("7"))
            .startDate(LocalDate.of(2025, 1, 1)).maturityDate(LocalDate.of(2026, 1, 1)).status("ACTIVE").build();
        FixedDeposit closed = FixedDeposit.builder().id(2L).bank("SBI").principal(new BigDecimal("100000")).rate(new BigDecimal("7"))
            .startDate(LocalDate.of(2025, 1, 1)).maturityDate(LocalDate.of(2026, 1, 1)).status("CLOSED").build();
        Map<Long, Classification> c = byId(DataAuditService.auditFds(List.of(lapsed, closed), TODAY));
        assertThat(c.get(1L)).isEqualTo(Classification.REQUIRES_RECONCILIATION);
        assertThat(c.get(2L)).isEqualTo(Classification.MISSING);
    }

    @Test
    @DisplayName("every record is counted once, and duplicates' overstatement is totalled")
    void summary() {
        LocalDate d = LocalDate.of(2026, 9, 1);
        var report = DataAuditService.summarise(DataAuditService.auditExpenses(List.of(
            exp(1, "m1", "870", d, "Swiggy"), exp(2, "m1", "870", d.plusDays(1), "Swiggy")), Set.of(), Set.of(),
            lines(Map.of("m1", 2), Map.of("m1", 1)), TODAY));
        assertThat(report.getTotalRecords()).isEqualTo(2);
        assertThat(report.getVerifiedRecords()).isEqualTo(1);
        assertThat(report.getDuplicateExpenseAmount()).isEqualByComparingTo("870");
        assertThat(report.getCounts().get(DataAuditService.Entity.EXPENSE).get(Classification.DUPLICATE)).isEqualTo(1);
    }

    @Test
    @DisplayName("ledger: the same broker trade reference read twice is a duplicate")
    void tradeReferenceDuplicate() {
        Holding h = Holding.builder().id(8L).symbol("INFY.NS").quantity(new BigDecimal("10")).averageCost(new BigDecimal("100")).build();
        Transaction a = t(1, Transaction.TransactionType.BUY, "2026-01-05", "10", "100", "note-a");
        Transaction b = t(2, Transaction.TransactionType.BUY, "2026-01-05", "10", "100", "note-b");
        a.getProvenance().setSourceReference("T123");
        b.getProvenance().setSourceReference("t123");
        Map<Long, Classification> c = DataAuditService.auditHolding(h, List.of(a, b), Set.of(), TODAY).stream()
            .filter(x -> x.getEntity() == DataAuditService.Entity.TRANSACTION)
            .collect(Collectors.toMap(Finding::getId, Finding::getClassification));
        assertThat(c.get(2L)).isEqualTo(Classification.DUPLICATE);
    }
}
