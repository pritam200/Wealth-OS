package com.marketai.cashflow.service;

import com.marketai.cashflow.dto.DailyProjection;
import com.marketai.cashflow.dto.ProjectedEvent;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PocketSmith-style day-by-day cash projection, built entirely from data the app already has —
 * no new ingestion. Starts from today's cash balance ({@link CashAccountRepository}, the same
 * canonical source {@code PortfolioContextService} uses) and walks forward adding:
 *   - SIP/PPF/NPS debits from {@link RecurringInvestmentRepository} (explicit schedule → known)
 *   - FD/RD maturity credits from their stored maturity date (explicit date → known)
 *   - recurring income detected from the last 3 months of {@link Income} history (inferred → estimated)
 *   - a smoothed daily expense run-rate from the last 3 months of {@link Expense} history,
 *     since there is no explicit "this expense repeats" marker to key off (inferred → estimated)
 */
@Service
@RequiredArgsConstructor
public class CashFlowForecastService {

    private static final int HISTORY_MONTHS = 3;
    private static final int MIN_OCCURRENCES_FOR_RECURRING_INCOME = 2;

    private final CashAccountRepository cashAccountRepository;
    private final IncomeRepository incomeRepository;
    private final ExpenseRepository expenseRepository;
    private final RecurringInvestmentRepository recurringInvestmentRepository;
    private final FixedDepositRepository fixedDepositRepository;
    private final RecurringDepositRepository recurringDepositRepository;

    @Transactional(readOnly = true)
    public List<DailyProjection> forecast(Long userId, int daysAhead) {
        LocalDate today = LocalDate.now();
        LocalDate end = today.plusDays(Math.max(0, daysAhead));

        BigDecimal startingBalance = nz(cashAccountRepository.sumBalanceByUser(userId));

        // Bucket every projected event by the date it lands on, computed once up front rather
        // than re-querying per day.
        Map<LocalDate, List<ProjectedEvent>> byDate = new LinkedHashMap<>();

        addRecurringIncome(userId, today, end, byDate);
        addExpenseRunRate(userId, today, end, byDate);
        addSipDebits(userId, today, end, byDate);
        addFdMaturities(userId, today, end, byDate);
        addRdMaturities(userId, today, end, byDate);

        List<DailyProjection> out = new ArrayList<>();
        BigDecimal running = startingBalance;
        for (LocalDate d = today; !d.isAfter(end); d = d.plusDays(1)) {
            List<ProjectedEvent> events = byDate.getOrDefault(d, List.of());
            for (ProjectedEvent e : events) running = running.add(e.getAmount());
            out.add(DailyProjection.builder()
                .date(d)
                .projectedBalance(running.setScale(2, RoundingMode.HALF_UP))
                .events(events)
                .hasEstimatedComponent(events.stream().anyMatch(ProjectedEvent::isEstimated))
                .build());
        }
        return out;
    }

    /* ── Recurring income (estimated — inferred from history, not an explicit schedule) ──── */

    private void addRecurringIncome(Long userId, LocalDate today, LocalDate end, Map<LocalDate, List<ProjectedEvent>> byDate) {
        LocalDate historyStart = today.minusMonths(HISTORY_MONTHS);
        List<Income> history = incomeRepository.findByUserIdAndIncomeDateBetweenOrderByIncomeDateDesc(userId, historyStart, today);

        Map<IncomeSource, List<Income>> bySource = new LinkedHashMap<>();
        for (Income i : history) bySource.computeIfAbsent(i.getSource(), k -> new ArrayList<>()).add(i);

        for (Map.Entry<IncomeSource, List<Income>> entry : bySource.entrySet()) {
            List<Income> rows = entry.getValue();
            if (rows.size() < MIN_OCCURRENCES_FOR_RECURRING_INCOME) continue;

            BigDecimal avgAmount = average(rows.stream().map(Income::getAmount).toList());
            int typicalDay = (int) Math.round(rows.stream().mapToInt(i -> i.getIncomeDate().getDayOfMonth()).average().orElse(1));
            typicalDay = Math.max(1, Math.min(28, typicalDay)); // stays valid in every month

            String label = entry.getKey().getLabel() + " (recurring, from history)";
            for (LocalDate occurrence = nextOccurrenceOnOrAfter(today.plusDays(1), typicalDay);
                 !occurrence.isAfter(end);
                 occurrence = occurrence.plusMonths(1)) {
                add(byDate, occurrence, ProjectedEvent.builder()
                    .label(label).amount(avgAmount).type("INCOME").estimated(true).build());
            }
        }
    }

    /* ── Expense run-rate (estimated — smoothed daily average per category, last 3 months) ── */

    private void addExpenseRunRate(Long userId, LocalDate today, LocalDate end, Map<LocalDate, List<ProjectedEvent>> byDate) {
        LocalDate historyStart = today.minusMonths(HISTORY_MONTHS);
        List<Expense> history = expenseRepository.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(userId, historyStart, today);
        long historyDays = Math.max(1, ChronoUnit.DAYS.between(historyStart, today));

        Map<ExpenseCategory, BigDecimal> totalsByCategory = new LinkedHashMap<>();
        for (Expense e : history) {
            // Same exclusion as ExpenseRepository.sumByUserIdAndDateRange — a CC-bill settlement
            // isn't new spend, it's paying off spend already counted elsewhere.
            if (e.getCategory() == ExpenseCategory.ACCOUNT_TRANSFER) continue;
            totalsByCategory.merge(e.getCategory(), nz(e.getAmount()), BigDecimal::add);
        }

        for (Map.Entry<ExpenseCategory, BigDecimal> entry : totalsByCategory.entrySet()) {
            BigDecimal dailyRate = entry.getValue().divide(BigDecimal.valueOf(historyDays), 4, RoundingMode.HALF_UP);
            if (dailyRate.compareTo(BigDecimal.ZERO) <= 0) continue;
            String label = entry.getKey().getLabel() + " (est. daily run-rate)";
            for (LocalDate d = today.plusDays(1); !d.isAfter(end); d = d.plusDays(1)) {
                add(byDate, d, ProjectedEvent.builder()
                    .label(label).amount(dailyRate.negate()).type("EXPENSE").estimated(true).build());
            }
        }
    }

    /* ── SIP/PPF/NPS debits (known — explicit schedule on RecurringInvestment) ───────────── */

    private void addSipDebits(Long userId, LocalDate today, LocalDate end, Map<LocalDate, List<ProjectedEvent>> byDate) {
        for (RecurringInvestment ri : recurringInvestmentRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            if (!"ACTIVE".equals(ri.getStatus())) continue;

            LocalDate scheduleEnd = ri.getTenureMonths() != null
                ? ri.getStartDate().plusMonths(ri.getTenureMonths())
                : end;
            LocalDate windowEnd = scheduleEnd.isBefore(end) ? scheduleEnd : end;

            for (LocalDate due = firstOccurrenceAfter(ri.getStartDate(), today);
                 !due.isAfter(windowEnd);
                 due = due.plusMonths(1)) {
                if (due.isBefore(today.plusDays(1))) continue;
                add(byDate, due, ProjectedEvent.builder()
                    .label(ri.getLabel() + " (" + ri.getType() + ")")
                    .amount(ri.getAmount().negate())
                    .type("SIP")
                    .estimated(false)
                    .build());
            }
        }
    }

    /* ── FD/RD maturity credits (known — explicit maturity date on the deposit) ──────────── */

    private void addFdMaturities(Long userId, LocalDate today, LocalDate end, Map<LocalDate, List<ProjectedEvent>> byDate) {
        for (FixedDeposit fd : fixedDepositRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            if (!"ACTIVE".equals(fd.getStatus())) continue;
            LocalDate maturity = fd.getMaturityDate();
            if (maturity == null || maturity.isBefore(today.plusDays(1)) || maturity.isAfter(end)) continue;

            BigDecimal amount = fd.getMaturityAmount() != null
                ? fd.getMaturityAmount()
                : computeFdMaturityValue(fd.getPrincipal(), fd.getRate(), fd.getCompounding(), fd.getStartDate(), maturity);

            add(byDate, maturity, ProjectedEvent.builder()
                .label("FD maturity — " + fd.getBank())
                .amount(amount)
                .type("FD_MATURITY")
                .estimated(false)
                .build());
        }
    }

    private void addRdMaturities(Long userId, LocalDate today, LocalDate end, Map<LocalDate, List<ProjectedEvent>> byDate) {
        for (RecurringDeposit rd : recurringDepositRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            if (!"ACTIVE".equals(rd.getStatus())) continue;
            LocalDate maturity = rd.getMaturityDate();
            if (maturity == null || maturity.isBefore(today.plusDays(1)) || maturity.isAfter(end)) continue;

            BigDecimal amount = rd.getMaturityAmount() != null
                ? rd.getMaturityAmount()
                : computeRdCorpus(rd.getMonthlyAmount(), rd.getRate(), rd.getTenureMonths());

            add(byDate, maturity, ProjectedEvent.builder()
                .label("RD maturity — " + rd.getBank())
                .amount(amount)
                .type("RD_MATURITY")
                .estimated(false)
                .build());
        }
    }

    /* ── Shared maturity-value math — same formulas as TrackingService, kept local so this
       service's read-only projection never depends on TrackingService's write-path state. ── */

    private BigDecimal computeFdMaturityValue(BigDecimal principal, BigDecimal rate, String compounding,
                                               LocalDate start, LocalDate maturity) {
        if (principal == null || rate == null || start == null) return nz(principal);
        double years = Math.max(0, ChronoUnit.DAYS.between(start, maturity) / 365.25);
        int n = "monthly".equalsIgnoreCase(compounding) ? 12 : "annually".equalsIgnoreCase(compounding) ? 1 : 4;
        double r = rate.doubleValue() / 100.0;
        double mat = principal.doubleValue() * Math.pow(1 + r / n, n * years);
        return BigDecimal.valueOf(mat).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal computeRdCorpus(BigDecimal monthly, BigDecimal annualRate, int months) {
        if (monthly == null || annualRate == null) return nz(monthly);
        double r = annualRate.doubleValue() / 100.0 / 12.0;
        double m = monthly.doubleValue();
        double corpus = r == 0 ? m * months : m * (Math.pow(1 + r, months) - 1) / r * (1 + r);
        return BigDecimal.valueOf(corpus).setScale(2, RoundingMode.HALF_UP);
    }

    /* ── Date helpers ─────────────────────────────────────────────────────────────────────── */

    /** The next date on/after {@code from} that falls on {@code dayOfMonth}, clamped to each
     *  month's real length (so day 31 lands on Feb 28/29, not an exception). */
    private LocalDate nextOccurrenceOnOrAfter(LocalDate from, int dayOfMonth) {
        LocalDate candidate = LocalDate.of(from.getYear(), from.getMonthValue(),
            Math.min(dayOfMonth, from.lengthOfMonth()));
        if (candidate.isBefore(from)) {
            LocalDate next = candidate.plusMonths(1);
            candidate = LocalDate.of(next.getYear(), next.getMonthValue(), Math.min(dayOfMonth, next.lengthOfMonth()));
        }
        return candidate;
    }

    /** First occurrence of a monthly schedule anchored at {@code startDate} that falls on or
     *  after {@code from} — same "anchor day, clamped to month length" rule as above. */
    private LocalDate firstOccurrenceAfter(LocalDate startDate, LocalDate from) {
        int anchorDay = startDate.getDayOfMonth();
        if (!startDate.isBefore(from)) return startDate;
        return nextOccurrenceOnOrAfter(from, anchorDay);
    }

    private static void add(Map<LocalDate, List<ProjectedEvent>> byDate, LocalDate date, ProjectedEvent event) {
        byDate.computeIfAbsent(date, k -> new ArrayList<>()).add(event);
    }

    private static BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }

    private static BigDecimal average(List<BigDecimal> values) {
        if (values.isEmpty()) return BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : values) sum = sum.add(nz(v));
        return sum.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
    }
}
