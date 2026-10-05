package com.marketai.dataplatform.service;

import com.marketai.dataplatform.domain.*;
import com.marketai.dataplatform.repo.LedgerIssueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Reconciliation findings with a lifecycle. A finding is raised once per stable key and updated
 * when seen again; it is closed by a recorded decision or by the system with a stated reason. A
 * person's IGNORE is respected: the same finding does not reopen unless it gets worse.
 */
@Service
@RequiredArgsConstructor
public class IssueService {

    private final LedgerIssueRepository repo;
    private final AuditService audit;

    public record Raise(Long userId, Long accountId, Long assetId, IssueType type, IssueSeverity severity, String key,
                        String title, String description, String expected, String observed, BigDecimal difference,
                        List<SuspectedCause> causes, Long transactionId, Long otherTransactionId) {}

    public LedgerIssue raise(Raise r) {
        LocalDateTime now = LocalDateTime.now();
        String causes = r.causes() == null || r.causes().isEmpty() ? null
            : r.causes().stream().map(Enum::name).collect(Collectors.joining(","));
        Optional<LedgerIssue> existing = repo.findByUserIdAndIssueKey(r.userId(), r.key());
        if (existing.isEmpty()) {
            return repo.save(LedgerIssue.builder().userId(r.userId()).accountId(r.accountId()).assetId(r.assetId())
                .type(r.type()).severity(r.severity()).status(IssueStatus.OPEN).issueKey(r.key()).title(r.title())
                .description(r.description()).expectedValue(r.expected()).observedValue(r.observed())
                .difference(r.difference()).suspectedCauses(causes).transactionId(r.transactionId())
                .otherTransactionId(r.otherTransactionId()).detectedAt(now).lastSeenAt(now).build());
        }
        LedgerIssue i = existing.get();
        boolean worse = r.severity().ordinal() > i.getSeverity().ordinal();
        boolean differenceChanged = i.getDifference() != null && r.difference() != null && i.getDifference().compareTo(r.difference()) != 0;
        i.setLastSeenAt(now);
        i.setSeverity(r.severity());
        i.setDescription(r.description());
        i.setExpectedValue(r.expected());
        i.setObservedValue(r.observed());
        i.setDifference(r.difference());
        i.setSuspectedCauses(causes);
        if (i.getStatus() == IssueStatus.IGNORED && !(worse || differenceChanged)) return repo.save(i);
        if (i.getStatus() == IssueStatus.RESOLVED || i.getStatus() == IssueStatus.AUTO_RESOLVED || i.getStatus() == IssueStatus.IGNORED) {
            i.setStatus(IssueStatus.OPEN);
            i.setResolvedAt(null); i.setResolutionAction(null); i.setResolutionNote(null); i.setResolvedBy(null);
            audit.record(i.getUserId(), AuditService.SYSTEM, "ISSUE", i.getId(), "REOPENED", null, null, "found again");
        }
        return repo.save(i);
    }

    /** Closes an open finding because the condition no longer holds, saying why. */
    public void autoResolve(Long userId, String key, String note) {
        repo.findByUserIdAndIssueKey(userId, key).filter(i -> i.getStatus().open()).ifPresent(i -> {
            i.setStatus(IssueStatus.AUTO_RESOLVED);
            i.setResolvedAt(LocalDateTime.now());
            i.setResolvedBy(AuditService.SYSTEM);
            i.setResolutionNote(note);
            repo.save(i);
            audit.record(userId, AuditService.SYSTEM, "ISSUE", i.getId(), "AUTO_RESOLVED", null, null, note);
        });
    }

    /** Records an issue that is already resolved by the system (for example a duplicate merged by an exact reference). */
    public LedgerIssue recordAutoResolved(Raise r, String note) {
        LedgerIssue i = raise(r);
        if (i.getStatus().open()) {
            i.setStatus(IssueStatus.AUTO_RESOLVED);
            i.setResolvedAt(LocalDateTime.now());
            i.setResolvedBy(AuditService.SYSTEM);
            i.setResolutionNote(note);
            i = repo.save(i);
        }
        return i;
    }

    public boolean hasOpen(Long userId, IssueType type, Long transactionId) {
        return repo.findByUserIdAndTypeAndStatusIn(userId, type, List.of(IssueStatus.OPEN, IssueStatus.IN_REVIEW))
            .stream().anyMatch(i -> transactionId.equals(i.getTransactionId()));
    }

    public List<LedgerIssue> open(Long userId) {
        return repo.findByUserIdAndStatusInOrderByLastSeenAtDesc(userId, List.of(IssueStatus.OPEN, IssueStatus.IN_REVIEW));
    }

    public List<LedgerIssue> list(Long userId, Collection<IssueStatus> statuses) {
        return repo.findByUserIdAndStatusInOrderByLastSeenAtDesc(userId, statuses);
    }

    public LedgerIssue get(Long userId, Long id) {
        return repo.findById(id).filter(i -> i.getUserId().equals(userId))
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Issue not found"));
    }

    public LedgerIssue markInReview(LedgerIssue i, String actor, String note) {
        i.setStatus(IssueStatus.IN_REVIEW);
        i.setResolvedAt(null); i.setResolvedBy(null);
        LedgerIssue saved = repo.save(i);
        audit.record(i.getUserId(), actor, "ISSUE", i.getId(), ResolutionAction.REVIEW.name(), IssueStatus.OPEN.name(), IssueStatus.IN_REVIEW.name(), note);
        return saved;
    }

    public LedgerIssue close(LedgerIssue i, IssueStatus status, ResolutionAction action, String note, String actor) {
        i.setStatus(status);
        i.setResolutionAction(action);
        i.setResolutionNote(note);
        i.setResolvedBy(actor);
        i.setResolvedAt(LocalDateTime.now());
        LedgerIssue saved = repo.save(i);
        audit.record(i.getUserId(), actor, "ISSUE", i.getId(), action.name(), null, status.name(), note);
        return saved;
    }
}
