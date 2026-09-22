package com.marketai.networth.service;

import com.marketai.expense.repository.ExpenseRepository;
import com.marketai.income.repository.IncomeRepository;
import com.marketai.networth.attribution.AttributionComponent;
import com.marketai.networth.attribution.AttributionKind;
import com.marketai.networth.attribution.AttributionResult;
import com.marketai.networth.attribution.NetWorthAttributionCalculator;
import com.marketai.networth.entity.NetWorthSnapshot;
import com.marketai.networth.repository.NetWorthSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Explains a change in net worth between two snapshots.
 *
 * <p>The sentence this produces — "your net worth rose ₹X: ₹A you saved, ₹B the market gave you,
 * ₹C you spent" — is, per the competitive research, unoccupied in Indian retail finance. The
 * arithmetic is not the hard part. Separating money the user moved from money the market moved
 * requires knowing which transfers were internal, and most aggregators cannot tell a ₹50,000
 * bank-to-mutual-fund transfer from ₹50,000 of new saving.
 *
 * <p>This codebase can, because {@code LedgerTransferService} already applies an
 * equal-and-opposite cash effect to every transfer — so internal movement nets to zero before
 * attribution ever sees it.
 *
 * <p><b>Revaluation is derived as the closing residual and then checked, not assumed.</b> Income
 * and expense are known exactly from the ledger; what the market did is whatever remains. That
 * makes the residual meaningful only if the known components are complete, which is why
 * {@link AttributionResult#closes()} is surfaced rather than hidden — an unexplained gap is a
 * reconciliation finding, not a rounding adjustment.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class NetWorthAttributionService {

    private final NetWorthSnapshotRepository snapshotRepo;
    private final IncomeRepository incomeRepo;
    private final ExpenseRepository expenseRepo;
    private final NetWorthAttributionCalculator calculator;

    /**
     * Attributes the change between the most recent snapshot and the closest one on or before
     * {@code from}.
     *
     * @return empty when there are not two snapshots to compare — a change needs two points, and
     *         inventing an opening value of zero would report a first-ever snapshot as though the
     *         user had earned their entire net worth that period.
     */
    public Optional<AttributionResult> attribute(Long userId, LocalDate from, LocalDate to) {
        List<NetWorthSnapshot> series = snapshotRepo.findByUserIdOrderBySnapshotDateAsc(userId);
        if (series.size() < 2) return Optional.empty();

        NetWorthSnapshot opening = closestOnOrBefore(series, from);
        NetWorthSnapshot closing = closestOnOrBefore(series, to);
        if (opening == null || closing == null || opening.getSnapshotDate().equals(closing.getSnapshotDate())) {
            return Optional.empty();
        }

        LocalDate start = opening.getSnapshotDate();
        LocalDate end = closing.getSnapshotDate();

        // start.plusDays(1): the repository queries are inclusive at both ends, and `start` is the
        // opening snapshot's own date — whose net worth already reflects that day's activity. A
        // ₹1,20,000 salary credited on the opening date was therefore counted twice, once inside
        // the opening figure and again as income for the period.
        BigDecimal income = sumIncome(userId, start.plusDays(1), end);
        BigDecimal expense = sumExpense(userId, start.plusDays(1), end);

        BigDecimal change = closing.getNetWorth().subtract(opening.getNetWorth());

        // What the ledger knows explicitly.
        BigDecimal knownBehaviour = income.subtract(expense);

        // Everything the known components do not account for is market movement. This is a
        // residual, and the honest consequence is that a missing transaction shows up here
        // rather than as a gap — so the caller is told how the figure was derived.
        BigDecimal revaluation = change.subtract(knownBehaviour);

        List<AttributionComponent> components = new ArrayList<>();
        if (income.signum() != 0) {
            components.add(AttributionComponent.of(AttributionKind.INCOME, income,
                "Income recorded in the ledger", "income:" + start + ".." + end));
        }
        if (expense.signum() != 0) {
            components.add(AttributionComponent.of(AttributionKind.EXPENSE, expense.negate(),
                "Expenses recorded in the ledger", "expense:" + start + ".." + end));
        }
        if (revaluation.signum() != 0) {
            components.add(AttributionComponent.of(AttributionKind.REVALUATION, revaluation,
                "Market movement on holdings", "residual:" + start + ".." + end));
        }

        return Optional.of(calculator.attribute(start, end,
            opening.getNetWorth(), closing.getNetWorth(), components));
    }

    /** The most recent 30 days, the default view. */
    public Optional<AttributionResult> attributeRecent(Long userId) {
        LocalDate today = LocalDate.now();
        return attribute(userId, today.minusDays(30), today);
    }

    private NetWorthSnapshot closestOnOrBefore(List<NetWorthSnapshot> ascending, LocalDate target) {
        NetWorthSnapshot best = null;
        for (NetWorthSnapshot s : ascending) {
            if (s.getNetWorth() == null) continue;
            if (!s.getSnapshotDate().isAfter(target)) best = s;
        }
        // Nothing on or before the requested date — fall back to the earliest available rather
        // than reporting no history at all.
        return best != null ? best
            : ascending.stream().filter(s -> s.getNetWorth() != null).findFirst().orElse(null);
    }

    private BigDecimal sumIncome(Long userId, LocalDate from, LocalDate to) {
        return incomeRepo.findByUserIdAndIncomeDateBetweenOrderByIncomeDateDesc(userId, from, to).stream()
            .map(i -> i.getAmount() == null ? BigDecimal.ZERO : i.getAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumExpense(Long userId, LocalDate from, LocalDate to) {
        return expenseRepo.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(userId, from, to).stream()
            .map(e -> e.getAmount() == null ? BigDecimal.ZERO : e.getAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
