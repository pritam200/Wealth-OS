package com.marketai.reconciliation.check;

import com.marketai.gmail.entity.ImportedTransactionFingerprint;
import com.marketai.gmail.repository.ImportedTransactionFingerprintRepository;
import com.marketai.reconciliation.dto.ReconciliationIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Surfaces transactions that {@code TransactionMatchScorer} imported but flagged
 * {@code NEEDS_REVIEW} — scored above the review threshold but below the confirm threshold
 * against an existing record, so per spec it was imported (never silently discarded) AND
 * flagged for a human to confirm it is genuinely a new transaction, not a near-duplicate.
 *
 * <p>{@link ImportedTransactionFingerprintRepository#findByUserIdAndDuplicateStateOrderByImportedAtAsc}
 * already existed for exactly this purpose (see its own Javadoc: "Rows awaiting human review") but
 * had no caller anywhere in the codebase — the flag was set on import and then read by nothing,
 * the same "silent" failure mode {@link TransactionConflictCheck} exists to close for conflicting
 * restatements. Without this check, a NEEDS_REVIEW row sat indistinguishable from a normal
 * transaction, with no path for the user to ever confirm or reject the match.
 */
@Component
@RequiredArgsConstructor
public class NeedsReviewFingerprintCheck implements ReconciliationCheck {

    private final ImportedTransactionFingerprintRepository fingerprintRepo;

    @Override public String id() { return "TRANSACTION_NEEDS_REVIEW"; }
    @Override public String domain() { return "LEDGER"; }
    @Override public String description() {
        return "A transaction was imported but resembles an existing one closely enough that it "
             + "needs a human to confirm it isn't the same payment counted twice";
    }

    @Override
    public List<ReconciliationIssue> run(Long userId) {
        return fingerprintRepo
            .findByUserIdAndDuplicateStateOrderByImportedAtAsc(userId, "NEEDS_REVIEW")
            .stream()
            .map(this::toIssue)
            .toList();
    }

    private ReconciliationIssue toIssue(ImportedTransactionFingerprint f) {
        String confidence = f.getMatchConfidence() != null
            ? String.format("%.0f%%", f.getMatchConfidence() * 100) : "unknown";
        return ReconciliationIssue.builder()
            .domain(domain()).type(id()).severity("MEDIUM")
            .referenceId(f.getId())
            .description(String.format(
                "%s resembles an earlier transaction (%s confidence match against record #%s) "
                    + "closely enough to need confirmation — imported, but not yet confirmed as "
                    + "a distinct transaction.",
                f.getDescription() != null ? f.getDescription() : (f.getType() + " " + f.getAmount()),
                confidence, f.getMatchedFingerprintId()))
            .build();
    }
}
