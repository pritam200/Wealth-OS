package com.marketai.reconciliation.check;

import com.marketai.ledger.entity.LedgerTransfer;
import com.marketai.ledger.repository.LedgerTransferRepository;
import com.marketai.reconciliation.dto.ReconciliationIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * A transfer recorded but never applied to the cash accounts.
 *
 * <p>{@code LedgerTransfer.applied} is the flag that makes the equal-and-opposite cash mutation
 * idempotent — it is what stops a replay from moving the money twice. The failure mode it
 * cannot prevent is the opposite one: a transfer row written while the mutation fails, leaving
 * {@code applied=false} forever.
 *
 * <p>That is worth finding because it breaks net-worth neutrality in the direction nothing else
 * notices. The money has left neither side, so the user's net worth is overstated by the amount
 * of the transfer and every screen agrees with every other screen about the wrong number.
 */
@Component
@RequiredArgsConstructor
public class UnappliedTransferCheck implements ReconciliationCheck {

    private final LedgerTransferRepository transferRepo;

    @Override public String id() { return "LEDGER_UNAPPLIED_TRANSFER"; }
    @Override public String domain() { return "LEDGER"; }
    @Override public String description() {
        return "A transfer was recorded but its cash movement never completed, so net worth is "
             + "overstated by the transfer amount";
    }

    @Override
    public List<ReconciliationIssue> run(Long userId) {
        List<ReconciliationIssue> issues = new ArrayList<>();
        for (LedgerTransfer t : transferRepo.findByUser_IdOrderByTransferDateDesc(userId)) {
            if (!Boolean.TRUE.equals(t.getApplied())) {
                issues.add(ReconciliationIssue.builder()
                    .domain(domain()).type(id()).severity("HIGH")
                    .referenceId(t.getId())
                    .description(String.format(
                        "Transfer of ₹%s on %s was recorded but never applied to your cash "
                            + "accounts — net worth is overstated by this amount until it is "
                            + "either applied or removed.",
                        t.getAmount() == null ? "?" : t.getAmount().toPlainString(),
                        t.getTransferDate()))
                    .build());
            }
        }
        return issues;
    }
}
