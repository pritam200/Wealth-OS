package com.marketai.reconciliation.check;

import com.marketai.document.entity.DocumentStatus;
import com.marketai.document.entity.FinancialDocument;
import com.marketai.document.repository.FinancialDocumentRepository;
import com.marketai.reconciliation.dto.ReconciliationIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Documents left mid-flight, and documents that failed and were never retried.
 *
 * <p>Both are invisible losses. A document stuck in PROCESSING because its worker died, or sat
 * in FAILED without being picked up again, represents a transaction the user believes was
 * imported. Nothing else reports it — the sync that abandoned it has long since finished and
 * reported success for everything else it handled.
 */
@Component
@RequiredArgsConstructor
public class StalledDocumentCheck implements ReconciliationCheck {

    private final FinancialDocumentRepository documentRepo;

    /** Generous enough that a slow sync in progress is not reported as stalled. */
    private static final int STALL_HOURS = 6;

    @Override public String id() { return "INGESTION_STALLED_DOCUMENT"; }
    @Override public String domain() { return "INGESTION"; }
    @Override public String description() {
        return "A document was picked up for processing or failed, and never reached the ledger "
             + "or the review queue";
    }

    @Override
    public List<ReconciliationIssue> run(Long userId) {
        List<ReconciliationIssue> issues = new ArrayList<>();

        for (FinancialDocument d : documentRepo.findStalled(LocalDateTime.now().minusHours(STALL_HOURS))) {
            if (!d.getUserId().equals(userId)) continue;
            issues.add(ReconciliationIssue.builder()
                .domain(domain()).type(id()).severity("HIGH")
                .referenceId(d.getId())
                .description(String.format(
                    "Document %s has been %s since %s. Whatever it contained has neither been "
                        + "imported nor queued for review.",
                    d.getSourceRef(), d.getStatus(), d.getStatusChangedAt()))
                .build());
        }

        for (FinancialDocument d : documentRepo.findByUserIdAndStatus(userId, DocumentStatus.FAILED)) {
            issues.add(ReconciliationIssue.builder()
                .domain(domain()).type("INGESTION_FAILED_DOCUMENT").severity("MEDIUM")
                .referenceId(d.getId())
                .description(String.format(
                    "Document %s failed after %d attempt(s): %s",
                    d.getSourceRef(), d.getAttempts(),
                    d.getLastError() == null ? "no error recorded" : d.getLastError()))
                .build());
        }
        return issues;
    }
}
