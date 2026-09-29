package com.marketai.reconciliation.service;

import com.marketai.reconciliation.dto.ReconciliationIssue;
import com.marketai.reconciliation.entity.ReconciliationIssueRecord;
import com.marketai.reconciliation.repository.ReconciliationIssueRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Runs every reconciliation check and keeps the findings as {@link ReconciliationIssueRecord}s:
 * new findings open, findings no longer present resolve, returning findings reopen.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationIssueService {

    private final ReconciliationService reconciliationService;
    private final ReconciliationIssueRecordRepository repo;

    /** Re-runs the checks and updates the stored issues. @return the issues currently open or acknowledged */
    @Transactional
    public List<ReconciliationIssueRecord> refresh(Long userId) {
        List<ReconciliationIssue> found = reconciliationService.checkAll(userId).getIssues();
        LocalDateTime now = LocalDateTime.now();

        Map<String, ReconciliationIssueRecord> stored = new HashMap<>();
        for (ReconciliationIssueRecord r : repo.findByUserId(userId)) stored.put(r.getIssueKey(), r);

        Set<String> seen = new HashSet<>();
        for (ReconciliationIssue issue : found) {
            String key = keyOf(issue);
            if (!seen.add(key)) continue;   // two findings with one identity: keep the first
            ReconciliationIssueRecord r = stored.get(key);
            if (r == null) {
                r = ReconciliationIssueRecord.builder()
                    .userId(userId).issueKey(key).firstSeenAt(now).status(ReconciliationIssueRecord.OPEN).build();
            } else if (ReconciliationIssueRecord.RESOLVED.equals(r.getStatus())) {
                r.setStatus(ReconciliationIssueRecord.OPEN);
                r.setResolvedAt(null);
            }
            r.setDomain(trim(issue.getDomain(), 40));
            r.setType(trim(issue.getType(), 80));
            r.setSeverity(issue.getSeverity() != null ? trim(issue.getSeverity(), 10) : "MEDIUM");
            r.setDescription(issue.getDescription());
            r.setReferenceId(issue.getReferenceId());
            r.setLastSeenAt(now);
            repo.save(r);
        }
        for (ReconciliationIssueRecord r : stored.values()) {
            if (!seen.contains(r.getIssueKey()) && !ReconciliationIssueRecord.RESOLVED.equals(r.getStatus())) {
                r.setStatus(ReconciliationIssueRecord.RESOLVED);
                r.setResolvedAt(now);
                repo.save(r);
            }
        }
        return current(userId);
    }

    public List<ReconciliationIssueRecord> current(Long userId) {
        return repo.findByUserIdAndStatusInOrderByLastSeenAtDesc(userId,
            List.of(ReconciliationIssueRecord.OPEN, ReconciliationIssueRecord.ACKNOWLEDGED));
    }

    public List<ReconciliationIssueRecord> recentlyResolved(Long userId) {
        return repo.findByUserIdAndStatusInOrderByLastSeenAtDesc(userId, List.of(ReconciliationIssueRecord.RESOLVED));
    }

    /** The user has seen it and accepts it for now; it stays listed until the check stops finding it. */
    @Transactional
    public ReconciliationIssueRecord acknowledge(Long userId, Long id, String note) {
        ReconciliationIssueRecord r = repo.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Issue not found"));
        if (ReconciliationIssueRecord.RESOLVED.equals(r.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This issue is already resolved");
        }
        r.setStatus(ReconciliationIssueRecord.ACKNOWLEDGED);
        r.setNote(trim(note, 500));
        return repo.save(r);
    }

    /**
     * Identity of a finding across runs. Findings about a specific record are keyed on it;
     * account-level findings ("3 items pending for 14 days") on their wording with the numbers
     * removed, so the count changing doesn't make it a new issue.
     */
    static String keyOf(ReconciliationIssue issue) {
        String base = issue.getDomain() + "|" + issue.getType() + "|";
        String rest = issue.getReferenceId() != null ? "ref:" + issue.getReferenceId()
            : "txt:" + Integer.toHexString(String.valueOf(issue.getDescription()).replaceAll("[\\d.,₹]+", "#").hashCode());
        return trim(base + rest, 200);
    }

    private static String trim(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
