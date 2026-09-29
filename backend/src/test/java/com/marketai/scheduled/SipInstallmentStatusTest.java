package com.marketai.scheduled;

import com.marketai.portfolio.entity.Transaction;
import com.marketai.scheduled.dto.InstallmentStatus;
import com.marketai.scheduled.entity.RecurringInvestment;
import com.marketai.scheduled.entity.RecurringInvestmentHistory;
import com.marketai.scheduled.service.RecurringInvestmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SipInstallmentStatusTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 5);
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 20);

    private static RecurringInvestment sip(String amount, String status) {
        return RecurringInvestment.builder().id(1L).type(RecurringInvestment.Type.SIP).label("Fund")
            .linkedSymbol("FUND.MF").amount(new BigDecimal(amount)).startDate(START).status(status).build();
    }

    private static Transaction buy(LocalDate d, String qty, String price) {
        return Transaction.builder().type(Transaction.TransactionType.BUY).transactionDate(d)
            .quantity(new BigDecimal(qty)).price(new BigDecimal(price)).build();
    }

    private static RecurringInvestmentHistory change(String field, String from, String to, LocalDate on) {
        return RecurringInvestmentHistory.builder().field(field).oldValue(from).newValue(to)
            .changedAt(on.atTime(10, 0)).build();
    }

    @SuppressWarnings("unchecked")
    private static List<InstallmentStatus> build(RecurringInvestment ri, List<RecurringInvestmentHistory> h,
                                                 List<Transaction> t) throws Exception {
        Method m = RecurringInvestmentService.class.getDeclaredMethod("buildSipInstallments",
            RecurringInvestment.class, List.class, List.class, LocalDate.class);
        m.setAccessible(true);
        return (List<InstallmentStatus>) m.invoke(null, ri, h, t, TODAY);
    }

    private static List<String> statuses(List<InstallmentStatus> rows) {
        return rows.stream().map(InstallmentStatus::getStatus).toList();
    }

    @Test
    @DisplayName("each month is judged against the amount in force then; a short purchase is PARTIAL")
    void amountAware() throws Exception {
        // ₹5,000 until 1 Apr, ₹10,000 after. April bought only ₹5,000.
        var history = List.of(change("amount", "5000", "10000", LocalDate.of(2026, 4, 1)));
        var txns = new ArrayList<>(List.of(
            buy(LocalDate.of(2026, 1, 5), "50", "100"), buy(LocalDate.of(2026, 2, 6), "50", "99.99"),
            buy(LocalDate.of(2026, 3, 5), "50", "100"), buy(LocalDate.of(2026, 4, 6), "50", "100"),
            buy(LocalDate.of(2026, 5, 5), "100", "100"), buy(LocalDate.of(2026, 6, 5), "100", "100")));

        List<InstallmentStatus> rows = build(sip("10000", "ACTIVE"), history, txns);

        assertThat(statuses(rows).subList(0, 6)).containsExactly("COMPLETED", "COMPLETED", "COMPLETED", "PARTIAL", "COMPLETED", "COMPLETED");
        assertThat(rows.get(0).getExpectedAmount()).isEqualByComparingTo("5000");
        assertThat(rows.get(3).getExpectedAmount()).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("paused months are PAUSED, a recorded failure is FAILED, a sale never counts as an instalment")
    void pausedFailedAndSales() throws Exception {
        var history = List.of(
            change("status", "ACTIVE", "PAUSED", LocalDate.of(2026, 3, 1)),
            change("status", "PAUSED", "ACTIVE", LocalDate.of(2026, 4, 30)),
            change("failed", "Insufficient funds", "2026-02-05", LocalDate.of(2026, 2, 8)));
        var txns = new ArrayList<>(List.of(
            buy(LocalDate.of(2026, 1, 5), "50", "100"),
            Transaction.builder().type(Transaction.TransactionType.SELL).transactionDate(LocalDate.of(2026, 5, 5))
                .quantity(new BigDecimal("50")).price(new BigDecimal("100")).build()));

        List<InstallmentStatus> rows = build(sip("5000", "ACTIVE"), history, txns);

        assertThat(statuses(rows)).containsExactly("COMPLETED", "FAILED", "PAUSED", "PAUSED", "MISSED", "MISSED", "UPCOMING");
        assertThat(rows.get(1).getNote()).isEqualTo("Insufficient funds");
    }

    @Test
    @DisplayName("nothing falls due after a cancellation")
    void cancelled() throws Exception {
        var history = List.of(change("status", "ACTIVE", "CANCELLED", LocalDate.of(2026, 3, 1)));
        List<InstallmentStatus> rows = build(sip("5000", "CANCELLED"), history,
            new ArrayList<>(List.of(buy(LocalDate.of(2026, 1, 5), "50", "100"), buy(LocalDate.of(2026, 2, 5), "50", "100"))));
        assertThat(statuses(rows)).containsExactly("COMPLETED", "COMPLETED");
    }
}
