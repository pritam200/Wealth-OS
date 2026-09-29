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
    private final com.marketai.rent.repository.RentScheduleRepository rentScheduleRepository;
    private final com.marketai.rent.repository.RentRepository rentRepository;

    /** Income that genuinely repeats month to month. Bonuses, sale proceeds, capital gains,
     *  dividends and "other" credits are one-offs: two in a quarter doesn't make them monthly,
     *  and projecting them every month overstated future cash by the full amount each month. */
    private static final java.util.Set<IncomeSource> RECURRING_INCOME = java.util.EnumSet.of(
        IncomeSource.SALARY, IncomeSource.RENTAL, IncomeSource.INTEREST,
        IncomeSource.FREELANCE, IncomeSource.BUSINESS);

    /** Floor on the run-rate window. A brand-new account with a few days of history would
     *  otherwise extrapolate one large purchase into a daily habit. */
    private static final int MIN_RUN_RATE_DAYS = 30;

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
        addRent(userId, today, end, byDate);

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
            if (!RECURRING_INCOME.contains(entry.getKey())) continue;
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
        // Divide by the span the history actually covers — the full 3-month window diluted the
        // rate for anyone whose records start more recently (a new account, a new family member).
        LocalDate earliest = history.stream().map(Expense::getExpenseDate).filter(d -> d != null)
            .min(LocalDate::compareTo).orElse(historyStart);
        long coveredDays = ChronoUnit.DAYS.between(earliest, today) + 1;
        long historyDays = Math.min(ChronoUnit.DAYS.between(historyStart, today),
            Math.max(MIN_RUN_RATE_DAYS, coveredDays));

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

            if (ri.getStartDate() == null || ri.getAmount() == null) continue;

            // Instalment k is always start.plusMonths(k), computed from the anchor each time:
            // stepping due = due.plusMonths(1) clamped a 29th–31st SIP to the 28th after February
            // and kept it there. A tenure of n months means instalments k = 0 … n-1.
            int maxInstalments = ri.getTenureMonths() != null ? ri.getTenureMonths() : Integer.MAX_VALUE;
            for (int k = 0; k < maxInstalments; k++) {
                LocalDate due = ri.getStartDate().plusMonths(k);
                if (due.isAfter(end)) break;
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

    /* ── Rent (known — explicit monthly schedule) ──────────────────────────────────────────
       Rent payments are booked to the Rent ledger, not as Expense rows, so the expense run-rate
       never sees them; without this block a ₹31,000/month rent was simply missing. A month
       already marked paid for the schedule is skipped. */

    private void addRent(Long userId, LocalDate today, LocalDate end, Map<LocalDate, List<ProjectedEvent>> byDate) {
        for (com.marketai.rent.entity.RentSchedule rs : rentScheduleRepository.findByUserIdAndActiveTrue(userId)) {
            if (rs.getAmount() == null || rs.getDueDayOfMonth() == null) continue;
            int day = Math.max(1, Math.min(31, rs.getDueDayOfMonth()));
            for (LocalDate monthStart = today.withDayOfMonth(1); !monthStart.isAfter(end); monthStart = monthStart.plusMonths(1)) {
                LocalDate due = monthStart.withDayOfMonth(Math.min(day, monthStart.lengthOfMonth()));
                if (due.isBefore(today.plusDays(1)) || due.isAfter(end)) continue;
                boolean paid = rentRepository.findByUserIdAndMonthAndScheduleId(userId, monthStart, rs.getId())
                    .map(r -> r.getPaidDate() != null).orElse(false);
                if (paid) continue;
                add(byDate, due, ProjectedEvent.builder()
                    .label("Rent" + (rs.getPaidTo() != null ? " — " + rs.getPaidTo() : ""))
                    .amount(rs.getAmount().negate())
                    .type("RENT")
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
