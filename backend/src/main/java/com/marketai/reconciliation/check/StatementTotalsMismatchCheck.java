package com.marketai.reconciliation.check;

import com.marketai.gmail.repository.PendingPdfRepository;
import com.marketai.gmail.repository.ProcessedEmailRepository;
import com.marketai.reconciliation.dto.ReconciliationIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Bank statements whose stated totals (debits, credits, opening + movement = closing) don't
 * match the lines extracted from them — the signal that a transaction line was skipped or
 * misread. Recorded per document at import time ({@code DocumentCounts.totalsCheck}).
 */
@Component
@RequiredArgsConstructor
public class StatementTotalsMismatchCheck implements ReconciliationCheck {

    private final ProcessedEmailRepository processedEmailRepo;
    private final PendingPdfRepository pendingPdfRepo;

    @Override public String id() { return "INGESTION_STATEMENT_TOTALS_MISMATCH"; }
    @Override public String domain() { return "INGESTION"; }
    @Override public String description() {
        return "A statement's own totals don't add up to the transactions read from it, so a line "
             + "may be missing or misread";
    }

    @Override
    public List<ReconciliationIssue> run(Long userId) {
        List<ReconciliationIssue> issues = new ArrayList<>();
        for (var pdf : pendingPdfRepo.findByUserIdAndCounts_TotalsCheck(userId, "MISMATCHED")) {
            issues.add(ReconciliationIssue.builder()
                .domain(domain()).type(id()).severity("HIGH").referenceId(pdf.getId())
                .description((pdf.getFilename() != null ? pdf.getFilename() : "A statement") + ": "
                    + pdf.getCounts().getTotalsDetail())
                .build());
        }
        for (var email : processedEmailRepo.findByUserIdAndCounts_TotalsCheck(userId, "MISMATCHED")) {
            // Its own type: the reference is a processed_emails id, which can equal a pending_pdfs
            // id above, and the issue identity is domain + type + reference.
            issues.add(ReconciliationIssue.builder()
                .domain(domain()).type(id() + "_EMAIL").severity("HIGH").referenceId(email.getId())
                .description((email.getSubject() != null ? "\"" + email.getSubject() + "\"" : "An email") + ": "
                    + email.getCounts().getTotalsDetail())
                .build());
        }
        return issues;
    }
}
