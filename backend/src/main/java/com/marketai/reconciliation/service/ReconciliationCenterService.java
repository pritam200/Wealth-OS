package com.marketai.reconciliation.service;

import com.marketai.ai.review.entity.ReviewStatus;
import com.marketai.ai.review.repository.EmailReviewItemRepository;
import com.marketai.common.jobs.ScheduledJobHealth;
import com.marketai.common.jobs.ScheduledJobHealthRepository;
import com.marketai.gmail.repository.ImportedTransactionFingerprintRepository;
import com.marketai.gmail.repository.PendingPdfRepository;
import com.marketai.gmail.repository.ProcessedEmailRepository;
import com.marketai.reconciliation.entity.ReconciliationIssueRecord;
import com.marketai.sync.entity.SyncJob;
import com.marketai.sync.service.SyncJobService;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Everything the Reconciliation Center shows: what ingestion has read and what became of every
 * extracted event, the open reconciliation issues, and the health of the background jobs.
 * Figures come from the stored per-document records — nothing is estimated.
 */
@Service
@RequiredArgsConstructor
public class ReconciliationCenterService {

    private final ProcessedEmailRepository processedEmailRepo;
    private final PendingPdfRepository pendingPdfRepo;
    private final EmailReviewItemRepository reviewRepo;
    private final ImportedTransactionFingerprintRepository fingerprintRepo;
    private final ReconciliationIssueService issueService;
    private final ScheduledJobHealthRepository jobHealthRepo;
    private final SyncJobService syncJobService;
    private final ReconciliationService reconciliationService;

    @Value @Builder
    public static class Ingestion {
        long emailsScanned;
        long emailsFailed;
        long attachments;
        long eventsExtracted;
        long imported;
        long duplicatesPrevented;
        long conflicts;
        /** Lines that could not be validated or saved (each is also in the review queue). */
        long failedEvents;
        long needsReview;
        long passwordFailures;
        long awaitingPassword;
        long unreadableScans;
        /** Documents by outcome — SUCCESS, PARTIAL_SUCCESS, RECONCILIATION_REQUIRED, FAILED, NO_TRANSACTION. */
        Map<String, Long> documentsByOutcome;
        /** Email-read records written before per-document counts existed, so not in the totals above. */
        long documentsWithoutCounts;
    }

    @Value @Builder
    public static class FailedEmail {
        String gmailMessageId;
        String sender;
        String subject;
        String reason;
        LocalDateTime processedAt;
    }

    /** One check and how it came out on the last run: passed when none of its findings is open. */
    @Value @Builder
    public static class CheckStatus {
        String id;
        String domain;
        String description;
        /** Open or acknowledged findings from it. */
        long openIssues;
        /** Findings from it the user ignored. */
        long ignoredIssues;
        boolean failedToRun;
    }

    @Value @Builder
    public static class Center {
        Ingestion ingestion;
        List<ReconciliationIssueRecord> openIssues;
        List<ReconciliationIssueRecord> ignoredIssues;
        List<ReconciliationIssueRecord> recentlyResolved;
        List<FailedEmail> failedEmails;
        List<ScheduledJobHealth> backgroundJobs;
        com.marketai.sync.controller.SyncJobController.SyncJobDto lastSync;
        List<CheckStatus> checks;
        /** When the checks last actually ran; null if they never have. */
        LocalDateTime checkedAt;
    }

    /**
     * @param refresh re-run the checks first (otherwise the stored issues are shown). Not in one
     *                transaction: the refresh runs in its own, so a refresh at the same moment
     *                storing the same new issue first fails only that attempt, which is retried.
     */
    public Center center(Long userId, boolean refresh) {
        if (refresh) {
            try {
                issueService.refresh(userId);
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // A refresh running at the same moment (the one after a sync) stored the same new
                // issue first. Running again sees what it stored.
                try {
                    issueService.refresh(userId);
                } catch (org.springframework.dao.DataIntegrityViolationException again) {
                    // Still racing: what is stored is current enough to show.
                }
            }
        }
        List<ReconciliationIssueRecord> open = issueService.current(userId);
        List<ReconciliationIssueRecord> ignored = issueService.ignored(userId);
        List<ReconciliationIssueRecord> resolved = issueService.recentlyResolved(userId).stream().limit(20).toList();

        List<FailedEmail> failed = processedEmailRepo
            .findByUserIdAndStatusOrderByProcessedAtDesc(userId, "FAILED", PageRequest.of(0, 50)).stream()
            .map(p -> FailedEmail.builder().gmailMessageId(p.getGmailMessageId()).sender(p.getSender())
                .subject(p.getSubject()).reason(p.getResultSummary()).processedAt(p.getProcessedAt()).build())
            .toList();

        List<SyncJob> recent = syncJobService.recentFor(userId, 1);

        return Center.builder()
            .ingestion(ingestion(userId))
            .openIssues(open)
            .ignoredIssues(ignored)
            .checks(checkStatuses(open, ignored))
            .recentlyResolved(resolved)
            .failedEmails(failed)
            .backgroundJobs(jobHealthRepo.findAll(org.springframework.data.domain.Sort.by("jobName")).stream()
                .map(ReconciliationCenterService::withoutErrorText).toList())
            .lastSync(recent.isEmpty() ? null : com.marketai.sync.controller.SyncJobController.SyncJobDto.from(recent.get(0)))
            .checkedAt(issueService.lastRunAt(userId))
            .build();
    }

    List<CheckStatus> checkStatuses(List<ReconciliationIssueRecord> open, List<ReconciliationIssueRecord> ignored) {
        List<CheckStatus> out = new ArrayList<>();
        for (var c : reconciliationService.describeChecks()) {
            out.add(CheckStatus.builder().id(c.id()).domain(c.domain()).description(c.description())
                .openIssues(open.stream().filter(r -> c.id().equals(r.getCheckId())).count())
                .ignoredIssues(ignored.stream().filter(r -> c.id().equals(r.getCheckId())).count())
                .failedToRun(open.stream().anyMatch(r -> c.id().equals(r.getCheckId()) && "CHECK_FAILED".equals(r.getType())))
                .build());
        }
        return out;
    }

    /**
     * Job health is one row per job across every account, and its error text names the accounts
     * that failed ("user 7: ..."). Each user sees whether a job is healthy, not other users'
     * errors; the detail stays in the server log.
     */
    static ScheduledJobHealth withoutErrorText(ScheduledJobHealth h) {
        return ScheduledJobHealth.builder().jobName(h.getJobName()).lastStartedAt(h.getLastStartedAt())
            .lastFinishedAt(h.getLastFinishedAt()).lastStatus(h.getLastStatus()).lastFailureAt(h.getLastFailureAt())
            .consecutiveFailures(h.getConsecutiveFailures())
            .lastError(h.getLastError() == null ? null : "PARTIAL".equals(h.getLastStatus())
                ? "Some items failed on the last run; the details are in the server log."
                : "The last run failed; the details are in the server log.")
            .build();
    }

    Ingestion ingestion(Long userId) {
        long[] email = totals(processedEmailRepo.eventTotals(userId), 7);
        long[] pdf = totals(pendingPdfRepo.eventTotals(userId), 6);

        Map<String, Long> pdfStatus = new HashMap<>();
        long attachments = 0;
        for (Object[] row : pendingPdfRepo.countByStatus(userId)) {
            long n = ((Number) row[1]).longValue();
            pdfStatus.put(String.valueOf(row[0]), n);
            attachments += n;
        }

        Map<String, Long> byOutcome = new TreeMap<>();
        long withoutCounts = 0;
        for (Object[] row : processedEmailRepo.countByOutcome(userId)) {
            long n = ((Number) row[1]).longValue();
            if (row[0] == null) withoutCounts += n;
            else byOutcome.merge(String.valueOf(row[0]), n, Long::sum);
        }

        return Ingestion.builder()
            .emailsScanned(email[0])
            .emailsFailed(processedEmailRepo.countByUserIdAndStatus(userId, "FAILED"))
            .attachments(attachments)
            .eventsExtracted(email[1] + pdf[0])
            .imported(email[2] + pdf[1])
            .duplicatesPrevented(email[3] + pdf[2])
            .conflicts(email[4] + pdf[3])
            .needsReview(reviewRepo.countByUserIdAndStatus(userId, ReviewStatus.PENDING))
            .failedEvents(email[6] + pdf[5])
            .passwordFailures(pdfStatus.getOrDefault("PASSWORD_FAILED", 0L))
            .awaitingPassword(pdfStatus.getOrDefault("NEEDS_PASSWORD", 0L))
            .unreadableScans(pdfStatus.getOrDefault("NEEDS_OCR", 0L))
            .documentsByOutcome(byOutcome)
            .documentsWithoutCounts(withoutCounts)
            .build();
    }

    private static long[] totals(List<Object[]> rows, int width) {
        long[] out = new long[width];
        if (rows == null || rows.isEmpty() || rows.get(0) == null) return out;
        Object[] row = rows.get(0);
        for (int i = 0; i < width && i < row.length; i++) {
            out[i] = row[i] == null ? 0 : ((Number) row[i]).longValue();
        }
        return out;
    }
}
