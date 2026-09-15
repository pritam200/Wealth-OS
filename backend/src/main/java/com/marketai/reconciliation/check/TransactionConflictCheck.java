package com.marketai.reconciliation.check;

import com.marketai.gmail.repository.ImportedTransactionFingerprintRepository;
import com.marketai.reconciliation.dto.ReconciliationIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Surfaces transactions where two documents reported the same rail-issued payment with
 * different financial content.
 *
 * <p>A UTR, RRN or UPI reference identifies exactly one payment, so two readings of it cannot
 * both be correct — one is a restatement, a correction, or a parser error on one side. The
 * importer keeps the originally verified record and refuses the second, which is right: booking
 * it would double-count and overwriting would destroy financial history.
 *
 * <p>What was missing is the telling. Without this check, a conflict is recorded on the
 * fingerprint row and read by nothing — which is the "silent" failure mode the whole
 * reconciliation layer exists to eliminate. A discrepancy the system noticed and did not
 * mention is worse than one it never noticed, because the user has no reason to look.
 */
@Component
@RequiredArgsConstructor
public class TransactionConflictCheck implements ReconciliationCheck {

    private final ImportedTransactionFingerprintRepository fingerprintRepo;

    @Override public String id() { return "TRANSACTION_CONFLICT"; }
    @Override public String domain() { return "LEDGER"; }
    @Override public String description() {
        return "Two documents reported the same payment reference with different details, so one "
             + "of them is wrong and the imported figure may need correcting";
    }

    @Override
    public List<ReconciliationIssue> run(Long userId) {
        return fingerprintRepo.findByUserIdAndConflictDetectedTrue(userId).stream()
            .map(f -> ReconciliationIssue.builder()
                .domain(domain()).type(id()).severity("HIGH")
                .referenceId(f.getId())
                .description(f.getConflictDetail() != null ? f.getConflictDetail()
                    : "A later document reported this payment differently. The original was kept.")
                .build())
            .toList();
    }
}
