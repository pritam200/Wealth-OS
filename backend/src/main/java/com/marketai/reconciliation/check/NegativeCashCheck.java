package com.marketai.reconciliation.check;

import com.marketai.ledger.entity.CashAccount;
import com.marketai.ledger.repository.CashAccountRepository;
import com.marketai.reconciliation.dto.ReconciliationIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A cash account holding a negative balance.
 *
 * <p>Not necessarily wrong — an overdraft is real — but for the accounts this app tracks it
 * almost always means an outflow was imported without its matching inflow. Typically a transfer
 * whose destination leg never arrived, or a statement imported from partway through its period
 * so the opening balance is missing.
 *
 * <p>Reported rather than clamped. Clamping to zero would make the number look plausible while
 * leaving the missing transaction undiscovered, and net worth silently wrong.
 */
@Component
@RequiredArgsConstructor
public class NegativeCashCheck implements ReconciliationCheck {

    private final CashAccountRepository cashAccountRepo;

    @Override public String id() { return "LEDGER_NEGATIVE_CASH"; }
    @Override public String domain() { return "LEDGER"; }
    @Override public String description() {
        return "A cash account is negative, which usually means an outflow was imported without "
             + "its matching inflow";
    }

    @Override
    public List<ReconciliationIssue> run(Long userId) {
        List<ReconciliationIssue> issues = new ArrayList<>();
        for (CashAccount a : cashAccountRepo.findByUser_Id(userId)) {
            BigDecimal balance = a.getBalance();
            if (balance != null && balance.compareTo(BigDecimal.ZERO) < 0) {
                issues.add(ReconciliationIssue.builder()
                    .domain(domain()).type(id()).severity("MEDIUM")
                    .referenceId(a.getId())
                    .description(String.format(
                        "Cash account '%s' shows ₹%s. If this is not a genuine overdraft, an "
                            + "inflow is missing — check for a transfer whose other side never "
                            + "imported, or a statement imported from mid-period.",
                        a.getName(), balance.toPlainString()))
                    .build());
            }
        }
        return issues;
    }
}
