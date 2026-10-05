package com.marketai.dataplatform.service;

import com.marketai.dataplatform.domain.*;
import com.marketai.dataplatform.repo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * What a person can do about a reconciliation finding: review, confirm, reject, merge, ignore,
 * or mark a manual adjustment. Every action is recorded in the audit trail with who and why, and
 * none deletes anything: a rejected transaction stays in the ledger, excluded from calculations.
 */
@Service
@RequiredArgsConstructor
public class ResolutionService {

    private final IssueService issues;
    private final CanonicalTransactionRepository txns;
    private final TransactionSourceRepository sources;
    private final FinancialAccountRepository accounts;
    private final HoldingSnapshotRepository snapshots;
    private final FamilyAccessService access;
    private final LedgerService ledger;
    private final HoldingService holdings;
    private final AuditService audit;
    private final LegacyApplyService legacyApply;

    /**
     * The user's explicit request to book one ledger transaction into the rest of the app (portfolio,
     * income or deposit tracker). Nothing does this on its own; only an active, non-rejected
     * transaction on an account the user may modify qualifies.
     */
    @Transactional
    public LegacyApplyService.Result addToPortfolio(Long userId, Long transactionId) {
        CanonicalTransaction t = txns.findById(transactionId)
            .filter(x -> x.getUserId().equals(userId) || access.canModify(userId, x.getAccountId()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found"));
        if (!access.canModify(userId, t.getAccountId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the account owner or a co-owner can do this");
        LegacyApplyService.Result r = legacyApply.apply(t);
        audit.record(userId, String.valueOf(userId), "TRANSACTION", t.getId(), r.applied() ? "APPLIED_TO_PORTFOLIO" : "NOT_APPLIED_TO_PORTFOLIO",
            null, null, r.message());
        return r;
    }

    @Transactional
    public LedgerIssue act(Long userId, Long issueId, ResolutionAction action, String note, Long mergeTargetId) {
        LedgerIssue issue = issues.get(userId, issueId);
        String actor = String.valueOf(userId);
        if (issue.getAccountId() != null && !access.canModify(userId, issue.getAccountId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the account owner or a co-owner can resolve this");
        if (!issue.getStatus().open() && action != ResolutionAction.REVIEW)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This issue is already " + issue.getStatus());

        switch (action) {
            case REVIEW -> { return issues.markInReview(issue, actor, note); }
            case IGNORE -> { return issues.close(issue, IssueStatus.IGNORED, action, note, actor); }
            case CONFIRM -> {
                String extra = confirm(userId, issue, note);
                if (extra != null) {
                    String full = (note == null || note.isBlank() ? "" : note + " — ") + extra;
                    return issues.close(issue, IssueStatus.RESOLVED, action, full.length() > 500 ? full.substring(0, 500) : full, actor);
                }
            }
            case REJECT -> reject(userId, issue, note);
            case MERGE -> merge(userId, issue, mergeTargetId, note);
            case MARK_MANUAL_ADJUSTMENT -> manualAdjustment(userId, issue, note);
        }
        return issues.close(issue, IssueStatus.RESOLVED, action, note, actor);
    }

    private String confirm(Long userId, LedgerIssue issue, String note) {
        String portfolioNote = null;
        switch (issue.getType()) {
            case MISSING_TRANSACTION, UNCONFIRMED_TRANSACTION, CONFLICTING_SOURCES -> {
                CanonicalTransaction t = own(userId, issue.getTransactionId());
                String before = t.getStatus() + "/" + t.getReconciliationStatus();
                t.setStatus(TxnStatus.CONFIRMED);
                // The person's confirmation is evidence, but not that of an institution: an authoritative
                // source verifies on its own; otherwise the transaction is MATCHED, never VERIFIED.
                t.setReconciliationStatus(t.getSourceType().authoritative() ? ReconStatus.VERIFIED : ReconStatus.MATCHED);
                t.setLastVerifiedAt(LocalDateTime.now());
                txns.save(t);
                audit.record(userId, String.valueOf(userId), "TRANSACTION", t.getId(), "CONFIRMED", before, t.getStatus() + "/" + t.getReconciliationStatus(), note);
                refresh(t);
                // A confirmed transaction the portfolio lacks is booked there too (only on this explicit confirmation).
                if (issue.getType() == IssueType.MISSING_TRANSACTION || issue.getType() == IssueType.UNCONFIRMED_TRANSACTION) {
                    LegacyApplyService.Result r = legacyApply.apply(t);
                    audit.record(userId, String.valueOf(userId), "TRANSACTION", t.getId(), r.applied() ? "APPLIED_TO_PORTFOLIO" : "NOT_APPLIED_TO_PORTFOLIO",
                        null, null, r.message());
                    portfolioNote = r.message();
                }
            }
            case POSSIBLE_DUPLICATE, DUPLICATE_TRANSACTION -> { /* kept as two genuine transactions */ }
            case HOLDING_MISMATCH, CASH_MISMATCH, VALUATION_MISMATCH -> { /* acknowledged; the ledger is untouched */ }
        }
        return portfolioNote;
    }

    private void reject(Long userId, LedgerIssue issue, String note) {
        if (issue.getTransactionId() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "There is no transaction to reject on this issue");
        CanonicalTransaction t = own(userId, issue.getTransactionId());
        TxnStatus before = t.getStatus();
        t.setStatus(TxnStatus.REJECTED);
        if (issue.getType() == IssueType.POSSIBLE_DUPLICATE || issue.getType() == IssueType.DUPLICATE_TRANSACTION) t.setReconciliationStatus(ReconStatus.DUPLICATE);
        txns.save(t);
        audit.record(userId, String.valueOf(userId), "TRANSACTION", t.getId(), "REJECTED", before.name(), TxnStatus.REJECTED.name(), note);
        refresh(t);
    }

    /** Folds one transaction into another: its sources move to the target, and it is marked a duplicate. */
    private void merge(Long userId, LedgerIssue issue, Long targetId, String note) {
        Long fromId = issue.getTransactionId();
        Long toId = targetId != null ? targetId : issue.getOtherTransactionId();
        if (fromId == null || toId == null || fromId.equals(toId))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose the transaction to merge into");
        CanonicalTransaction from = own(userId, fromId), to = own(userId, toId);
        if (!java.util.Objects.equals(from.getAssetId(), to.getAssetId()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "These are different assets and cannot be merged");
        for (TransactionSource s : sources.findByTransactionIdOrderByIdAsc(from.getId())) {
            if (sources.existsByTransactionIdAndRawRecordId(to.getId(), s.getRawRecordId())) continue;
            s.setTransactionId(to.getId());
            s.setMatchKind("MANUAL_MERGE");
            sources.save(s);
        }
        from.setStatus(TxnStatus.REJECTED);
        from.setReconciliationStatus(ReconStatus.DUPLICATE);
        txns.save(from);
        audit.record(userId, String.valueOf(userId), "TRANSACTION", from.getId(), "MERGED_INTO", null, String.valueOf(to.getId()), note);
        audit.record(userId, String.valueOf(userId), "TRANSACTION", to.getId(), "MERGED_FROM", null, String.valueOf(from.getId()), note);
        ledger.recompute(to, LocalDateTime.now());
        refresh(to);
    }

    /**
     * Records an explicit adjustment so the ledger agrees with the institution's holding. It is a
     * visible MANUAL transaction, dated today, attributable to the person who made it — not a
     * silent edit of either side.
     */
    private void manualAdjustment(Long userId, LedgerIssue issue, String note) {
        if (issue.getType() != IssueType.HOLDING_MISMATCH || issue.getDifference() == null || issue.getAccountId() == null || issue.getAssetId() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A manual adjustment applies only to a holding mismatch");
        BigDecimal diff = issue.getDifference();   // reported - calculated
        FinancialAccount account = accounts.findById(issue.getAccountId()).orElseThrow();
        HoldingSnapshot calc = snapshots.findByAccountIdAndAssetIdAndBasis(account.getId(), issue.getAssetId(), BasisType.LEDGER_CALCULATED).orElse(null);
        BigDecimal price = calc != null && calc.getAverageCost() != null && calc.getAverageCost().signum() > 0 ? calc.getAverageCost()
            : snapshots.findByAccountIdAndAssetIdAndBasis(account.getId(), issue.getAssetId(), BasisType.INSTITUTION_REPORTED)
                .map(HoldingSnapshot::getCurrentPrice).orElse(BigDecimal.ZERO);
        BigDecimal qty = diff.abs();
        BigDecimal gross = price.multiply(qty).setScale(2, java.math.RoundingMode.HALF_UP);
        CanonicalTransaction adj = txns.save(CanonicalTransaction.builder().userId(account.getOwnerUserId()).familyId(account.getFamilyId())
            .accountId(account.getId()).assetId(issue.getAssetId())
            .transactionType(diff.signum() > 0 ? TransactionType.BUY : TransactionType.SELL).transactionDate(java.time.LocalDate.now())
            .quantity(qty).unitPrice(price).grossAmount(gross).netAmount(gross)
            .sourceType(SourceType.MANUAL).sourceProvider("manual-adjustment").sourceReference("adjustment-issue-" + issue.getId())
            .ingestedAt(LocalDateTime.now()).confidence(SourceType.MANUAL.baseConfidence())
            .status(TxnStatus.CONFIRMED).reconciliationStatus(ReconStatus.MATCHED)
            .notes("Manual adjustment to match the institution's reported holding" + (note == null ? "" : ": " + note)).build());
        audit.record(userId, String.valueOf(userId), "TRANSACTION", adj.getId(), "MANUAL_ADJUSTMENT", null,
            adj.getTransactionType() + " " + qty.stripTrailingZeros().toPlainString(), "resolves issue " + issue.getId());
        holdings.refresh(account.getOwnerUserId(), account.getId(), issue.getAssetId());
    }

    private void refresh(CanonicalTransaction t) {
        if (t.getAssetId() != null) holdings.refresh(t.getUserId(), t.getAccountId(), t.getAssetId());
    }

    private CanonicalTransaction own(Long userId, Long id) {
        CanonicalTransaction t = id == null ? null : txns.findById(id).orElse(null);
        if (t == null || !access.canModify(userId, t.getAccountId()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found");
        return t;
    }

    public List<LedgerIssue> open(Long userId) { return issues.open(userId); }
}
