package com.marketai.reconciliation.check;

import com.marketai.investmentplan.entity.PlannedInvestment;
import com.marketai.investmentplan.repository.InvestmentReconciliationRepository;
import com.marketai.investmentplan.repository.PlannedInvestmentRepository;
import com.marketai.ledger.entity.CashAccount;
import com.marketai.ledger.repository.CashAccountRepository;
import com.marketai.reconciliation.dto.ReconciliationIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Whether this month's still-open planned investments (spec §14) are realistically funded —
 * per source account, still-unmatched planned amount vs. that account's current balance.
 * Read-only monitoring, never moves money or auto-executes anything (spec §14's own
 * requirement). Deliberately covers planned investments only, not planned expenses — there is
 * no equivalent "planned expense" concept yet (Expense rows are only ever actual, not
 * forward-looking), so this check cannot overstate confidence by guessing at those.
 */
@Component
@RequiredArgsConstructor
public class FundingShortfallCheck implements ReconciliationCheck {

    private final PlannedInvestmentRepository planRepository;
    private final InvestmentReconciliationRepository reconciliationRepository;
    private final CashAccountRepository cashAccountRepo;

    @Override public String id() { return "INVESTMENT_PLAN_FUNDING_SHORTFALL"; }
    @Override public String domain() { return "LEDGER"; }
    @Override public String description() {
        return "This month's still-open planned investments exceed the funding account's current balance";
    }

    @Override
    public List<ReconciliationIssue> run(Long userId) {
        LocalDate month = LocalDate.now().withDayOfMonth(1);
        List<PlannedInvestment> open = planRepository.findByUserIdAndMonthAndStatusIn(userId, month,
            List.of(PlannedInvestment.PlanStatus.PLANNED, PlannedInvestment.PlanStatus.PARTIAL));

        Map<Long, BigDecimal> remainingByAccount = new HashMap<>();
        for (PlannedInvestment plan : open) {
            if (plan.getSourceAccountId() == null) continue; // no account named — nothing to check funding against
            BigDecimal matched = nz(reconciliationRepository.sumMatchedAmountByPlanId(plan.getId()));
            BigDecimal remaining = plan.getPlannedAmount().subtract(matched);
            if (remaining.signum() <= 0) continue;
            remainingByAccount.merge(plan.getSourceAccountId(), remaining, BigDecimal::add);
        }

        List<ReconciliationIssue> issues = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> entry : remainingByAccount.entrySet()) {
            CashAccount account = cashAccountRepo.findById(entry.getKey()).orElse(null);
            if (account == null) continue;
            BigDecimal balance = nz(account.getBalance());
            BigDecimal remainingPlanned = entry.getValue();
            if (remainingPlanned.compareTo(balance) > 0) {
                BigDecimal shortfall = remainingPlanned.subtract(balance);
                issues.add(ReconciliationIssue.builder()
                    .domain(domain()).type(id()).severity("MEDIUM")
                    .referenceId(account.getId())
                    .description(String.format(
                        "'%s' has ₹%s available but ₹%s of this month's planned investments are still "
                            + "unmatched — a ₹%s shortfall if all of them still go through.",
                        account.getName(), balance.toPlainString(), remainingPlanned.toPlainString(),
                        shortfall.toPlainString()))
                    .build());
            }
        }
        return issues;
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
