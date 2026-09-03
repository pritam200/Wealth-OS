package com.marketai.gmail.repository;

import com.marketai.gmail.entity.ProcessedEmail;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ProcessedEmailRepository extends JpaRepository<ProcessedEmail, Long> {
    boolean existsByUserIdAndGmailMessageId(Long userId, String gmailMessageId);
    List<ProcessedEmail> findByUserIdOrderByProcessedAtDesc(Long userId, PageRequest page);
    long countByUserIdAndStatus(Long userId, String status);
    void deleteByUserId(Long userId);
    void deleteByUserIdAndStatusIn(Long userId, List<String> statuses);

    Optional<ProcessedEmail> findByUserIdAndGmailMessageId(Long userId, String gmailMessageId);

    // Used by the reconciliation report to split the generic SKIPPED status into its
    // sub-cases: intentionally-excluded senders (type=EXCLUDED) and PDF attachments queued
    // for password unlock (matchedParser="PendingPdf", whose real fate is tracked on the
    // PendingPdf row, not here) — so "Unparsed" only counts genuine parsing gaps.
    long countByUserIdAndStatusAndType(Long userId, String status, String type);
    long countByUserIdAndStatusAndMatchedParser(Long userId, String status, String matchedParser);

    // Detail rows for the Failed/Unparsed bucket of the reconciliation report, most recent
    // first, capped by the caller's PageRequest.
    List<ProcessedEmail> findByUserIdAndStatusInOrderByProcessedAtDesc(Long userId, List<String> statuses, PageRequest page);
}
