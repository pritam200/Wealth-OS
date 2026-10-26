package com.marketai.reconciliation.service;

import com.marketai.reconciliation.dto.ReconciliationIssue;
import com.marketai.reconciliation.entity.ReconciliationIssueRecord;
import com.marketai.reconciliation.entity.ReconciliationRun;
import com.marketai.reconciliation.repository.ReconciliationIssueRecordRepository;
import com.marketai.reconciliation.repository.ReconciliationRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Runs every reconciliation check and keeps the findings as {@link ReconciliationIssueRecord}s:
 * new findings open, findings no longer present resolve, returning findings reopen. A finding the
 * user ignored stays ignored for as long as the check keeps finding it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationIssueService {

    private final ReconciliationService reconciliationService;
    private final ReconciliationIssueRecordRepository repo;
    /** When the checks last ran, per user. Optional so a bare unit test needn't provide it. */
    @Autowired(required = false)
    private ReconciliationRunRepository runRepo;

    /**
     * Re-runs the checks and updates the stored issues, in a transaction of its own: two refreshes
     * at once (the one after a sync, and a page opening) may both try to store the same new issue,
     * and the loser's failure must not poison the caller's transaction.
     *
     * @return the issues currently open or acknowledged
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
            r.setCheckId(trim(issue.getCheckId(), 80));
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
        if (runRepo != null) runRepo.save(ReconciliationRun.builder().userId(userId).lastRunAt(now).build());
        return current(userId);
    }

    /** When the checks last actually ran for the user, or null if they never have. */
    public LocalDateTime lastRunAt(Long userId) {
        return runRepo == null ? null : runRepo.findById(userId).map(ReconciliationRun::getLastRunAt).orElse(null);
    }

    public List<ReconciliationIssueRecord> current(Long userId) {
        return repo.findByUserIdAndStatusInOrderByLastSeenAtDesc(userId,
            List.of(ReconciliationIssueRecord.OPEN, ReconciliationIssueRecord.ACKNOWLEDGED));
    }

    public List<ReconciliationIssueRecord> ignored(Long userId) {
        return repo.findByUserIdAndStatusInOrderByLastSeenAtDesc(userId, List.of(ReconciliationIssueRecord.IGNORED));
    }

    public List<ReconciliationIssueRecord> recentlyResolved(Long userId) {
        return repo.findByUserIdAndStatusInOrderByLastSeenAtDesc(userId, List.of(ReconciliationIssueRecord.RESOLVED));
    }

    /** The user has seen it and accepts it for now; it stays listed until the check stops finding it. */
    @Transactional
    public ReconciliationIssueRecord acknowledge(Long userId, Long id, String note) {
        return move(userId, id, ReconciliationIssueRecord.ACKNOWLEDGED, note);
    }

    /**
     * The user says it isn't a problem: it leaves the open list and stays out of it while the check
     * keeps finding it. If the finding goes away and later comes back, it opens again.
     */
    @Transactional
    public ReconciliationIssueRecord ignore(Long userId, Long id, String note) {
        return move(userId, id, ReconciliationIssueRecord.IGNORED, note);
    }

    /** Back to the open list, from acknowledged or ignored. */
    @Transactional
    public ReconciliationIssueRecord reopen(Long userId, Long id) {
        ReconciliationIssueRecord r = own(userId, id);
        if (ReconciliationIssueRecord.RESOLVED.equals(r.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This issue is resolved; it reopens by itself if the check finds it again");
        }
        r.setStatus(ReconciliationIssueRecord.OPEN);
        return repo.save(r);
    }

    private ReconciliationIssueRecord move(Long userId, Long id, String status, String note) {
        ReconciliationIssueRecord r = own(userId, id);
        if (ReconciliationIssueRecord.RESOLVED.equals(r.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This issue is already resolved");
        }
        r.setStatus(status);
        if (note != null && !note.isBlank()) r.setNote(trim(note.trim(), 500));
        return repo.save(r);
    }

    private ReconciliationIssueRecord own(Long userId, Long id) {
        return repo.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Issue not found"));
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
