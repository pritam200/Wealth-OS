package com.marketai.gmail.repository;

import com.marketai.gmail.entity.ProcessedEmail;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface ProcessedEmailRepository extends JpaRepository<ProcessedEmail, Long> {
    boolean existsByUserIdAndGmailMessageId(Long userId, String gmailMessageId);
    List<ProcessedEmail> findByUserIdOrderByProcessedAtDesc(Long userId, PageRequest page);
    void deleteByUserId(Long userId);
    void deleteByUserIdAndStatusIn(Long userId, List<String> statuses);

    Optional<ProcessedEmail> findByUserIdAndGmailMessageId(Long userId, String gmailMessageId);

    List<ProcessedEmail> findByUserIdAndGmailMessageIdIn(Long userId, java.util.Collection<String> gmailMessageIds);

    // Used by the reconciliation report to split the generic SKIPPED status into its
    // sub-cases: intentionally-excluded senders (type=EXCLUDED) and PDF attachments queued
    // for password unlock (matchedParser="PendingPdf", whose real fate is tracked on the
    // PendingPdf row, not here) — so "Unparsed" only counts genuine parsing gaps.
    long countByUserIdAndStatusAndType(Long userId, String status, String type);
    long countByUserIdAndStatusAndMatchedParser(Long userId, String status, String matchedParser);

    // Detail rows for the Failed/Unparsed bucket of the reconciliation report, most recent
    // first, capped by the caller's PageRequest.
    List<ProcessedEmail> findByUserIdAndStatusInOrderByProcessedAtDesc(Long userId, List<String> statuses, PageRequest page);

    // Mirrors GmailSyncService's own "permanentlyResolved" test: every message id that a
    // scheduled backlog retry should re-attempt, regardless of how long ago it was first
    // processed (unlike a window-bounded rescan, which only ever looks back a fixed period).
    // `matchedParser` is null for the common case of "no parser matched at all", so the
    // PendingPdf exclusion must tolerate null rather than losing those rows to SQL's
    // three-valued NULL <> 'x' logic.
    @Query("select p.gmailMessageId from ProcessedEmail p where p.userId = :userId "
        + "and p.status <> 'IMPORTED' "
        + "and not (p.status = 'SKIPPED' and p.type = 'EXCLUDED') "
        + "and (p.matchedParser is null or p.matchedParser <> 'PendingPdf')")
    List<String> findRetryableMessageIds(@Param("userId") Long userId);

    List<ProcessedEmail> findByUserIdAndCounts_TotalsCheck(Long userId, String totalsCheck);

    /** Totals across every email read for this user: count, then extracted, imported, duplicate,
     *  conflict, needs-review and failed event counts. */
    @Query("select count(p), coalesce(sum(p.counts.extracted), 0), coalesce(sum(p.counts.imported), 0), "
        + "coalesce(sum(p.counts.duplicates), 0), coalesce(sum(p.counts.conflicts), 0), "
        + "coalesce(sum(p.counts.needsReview), 0), coalesce(sum(p.counts.failed), 0) "
        + "from ProcessedEmail p where p.userId = :userId")
    List<Object[]> eventTotals(@Param("userId") Long userId);

    long countByUserIdAndStatus(Long userId, String status);

    /** Documents by outcome (SUCCESS, PARTIAL_SUCCESS, RECONCILIATION_REQUIRED, FAILED, ...). */
    @Query("select p.counts.outcome, count(p) from ProcessedEmail p where p.userId = :userId group by p.counts.outcome")
    List<Object[]> countByOutcome(@Param("userId") Long userId);

    List<ProcessedEmail> findByUserIdAndStatusOrderByProcessedAtDesc(Long userId, String status, PageRequest page);
}
