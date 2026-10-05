package com.marketai.dataplatform.service;

import com.marketai.dataplatform.domain.*;
import com.marketai.dataplatform.pipeline.*;
import com.marketai.dataplatform.pipeline.ConfidenceCalculator.Observation;
import com.marketai.dataplatform.pipeline.ReconciliationEngine.Evaluation;
import com.marketai.dataplatform.repo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Writes the canonical ledger. A transaction is created once; every further report of it from any
 * source becomes a {@link TransactionSource} on the same row, and the row's figures, status,
 * confidence and reconciliation state are re-derived from its sources by the rules in
 * {@link ReconciliationEngine}. Nothing is deleted.
 */
@Service
@RequiredArgsConstructor
public class LedgerService {

    private static final List<TxnStatus> COUNTING = List.of(TxnStatus.PENDING_RECONCILIATION, TxnStatus.CONFIRMED);

    private final CanonicalTransactionRepository txns;
    private final TransactionSourceRepository sources;
    private final FinancialAccountRepository accounts;
    private final ReconciliationEngine engine;
    private final ConfidenceCalculator confidence;
    private final IssueService issues;
    private final AuditService audit;
    private final IngestionEvents events;
    private final AccountResolver accountResolver;

    @Value("${wealthos.data.reconciliation.grace-days:7}")
    private int graceDays = 7;

    /** Unconfirmed transactions older than this are counted in the data-quality score but not raised as individual issues. */
    @Value("${wealthos.data.reconciliation.unconfirmed-issue-window-days:90}")
    private int issueWindowDays = 90;

    public enum Outcome { CREATED, MATCHED, UPDATED }

    public record Applied(Outcome outcome, CanonicalTransaction entry, Long previousAccountId) {}

    /** Optional description of what an authoritative feed covers, for detecting what it lacks. */
    public record Coverage(LocalDate from, LocalDate to) {}

    /* ───────────── matching pool ───────────── */

    public List<LedgerEntryView> pool(Long userId, Long assetId, NormalizedTransaction nt) {
        Map<Long, CanonicalTransaction> found = new LinkedHashMap<>();
        LocalDate d = nt.getTransactionDate();
        LocalDate from = d.minusDays(Tolerances.DATE_DAYS + 5), to = d.plusDays(Tolerances.DATE_DAYS + 5);
        List<CanonicalTransaction> near = assetId != null
            ? txns.findByUserIdAndAssetIdAndTransactionDateBetween(userId, assetId, from, to)
            : txns.findByUserIdAndAssetIdIsNullAndTransactionDateBetween(userId, from, to);
        near.forEach(t -> found.put(t.getId(), t));
        if (nt.getSourceReference() != null && !nt.getSourceReference().isBlank())
            txns.findByAnySourceReference(userId, nt.getSourceReference().trim()).forEach(t -> found.putIfAbsent(t.getId(), t));
        return views(found.values());
    }

    List<LedgerEntryView> views(Collection<CanonicalTransaction> list) {
        if (list.isEmpty()) return List.of();
        Map<Long, List<TransactionSource>> byTxn = sources.findByTransactionIdIn(list.stream().map(CanonicalTransaction::getId).toList())
            .stream().collect(Collectors.groupingBy(TransactionSource::getTransactionId));
        Map<Long, FinancialAccount> accts = accounts.findAllById(list.stream().map(CanonicalTransaction::getAccountId).distinct().toList())
            .stream().collect(Collectors.toMap(FinancialAccount::getId, a -> a));
        List<LedgerEntryView> out = new ArrayList<>();
        for (CanonicalTransaction t : list) {
            FinancialAccount a = accts.get(t.getAccountId());
            out.add(new LedgerEntryView(t.getId(), t.getAccountId(), a != null && !AccountResolver.isPlaceholder(a), t.getAssetId(),
                t.getTransactionType(), t.getStatus(), t.getTransactionDate(), t.getQuantity(), t.getGrossAmount(), t.getNetAmount(),
                t.getRatioFrom(), t.getRatioTo(),
                byTxn.getOrDefault(t.getId(), List.of()).stream()
                    .map(s -> new LedgerEntryView.Source(s.getSourceType(), s.getSourceProvider(), s.getSourceReference())).toList()));
        }
        return out;
    }

    /* ───────────── apply a decision ───────────── */

    public Applied apply(RawFinancialData raw, NormalizedTransaction nt, AccountResolver.Resolved acct, Long assetId,
                         TransactionMatcher.Decision d, Coverage coverage) {
        LocalDateTime now = LocalDateTime.now();
        String explanation = d.explanationText();
        CanonicalTransaction entry;
        Outcome outcome;
        Long previousAccountId = null;

        switch (d.kind()) {
            case SAME_SOURCE_REDELIVERY -> {
                entry = txns.findById(d.matchedId()).orElseThrow();
                TransactionSource existing = sources.findByTransactionIdOrderByIdAsc(entry.getId()).stream()
                    .filter(s -> s.getSourceType() == nt.getSourceType() && eqi(s.getSourceProvider(), nt.getSourceProvider())
                        && eqi(s.getSourceReference(), nt.getSourceReference()))
                    .findFirst().orElse(null);
                if (existing != null) sources.save(copyFigures(existing, nt, raw.getId(), d.kind().name(), explanation));
                else sources.save(newSource(entry.getId(), raw.getId(), nt, d.kind().name(), explanation));
                outcome = Outcome.UPDATED;
            }
            case EXACT_REFERENCE, COMPOSITE_EXACT, COMPOSITE_TOLERANT -> {
                entry = txns.findById(d.matchedId()).orElseThrow();
                if (!sources.existsByTransactionIdAndRawRecordId(entry.getId(), raw.getId()))
                    sources.save(newSource(entry.getId(), raw.getId(), nt, d.kind().name(), explanation));
                if (acct.specific() && !entry.getAccountId().equals(acct.account().getId())
                        && accounts.findById(entry.getAccountId()).map(AccountResolver::isPlaceholder).orElse(false)) {
                    audit.record(entry.getUserId(), AuditService.SYSTEM, "TRANSACTION", entry.getId(), "ACCOUNT_REFINED",
                        "account " + entry.getAccountId(), "account " + acct.account().getId(), "a source named the specific account");
                    previousAccountId = entry.getAccountId();
                    entry.setAccountId(acct.account().getId());
                }
                if (nt.isReversal() && entry.getStatus() != TxnStatus.REVERSED) {
                    audit.record(entry.getUserId(), AuditService.SYSTEM, "TRANSACTION", entry.getId(), "REVERSED",
                        entry.getStatus().name(), TxnStatus.REVERSED.name(), "reported reversed by " + nt.getSourceType());
                    entry.setStatus(TxnStatus.REVERSED);
                }
                outcome = Outcome.MATCHED;
            }
            default -> {
                entry = txns.save(CanonicalTransaction.builder()
                    .userId(raw.getUserId()).familyId(acct.account().getFamilyId()).accountId(acct.account().getId()).assetId(assetId)
                    .transactionType(nt.getType()).transactionDate(nt.getTransactionDate()).settlementDate(nt.getSettlementDate())
                    .quantity(nt.getQuantity()).unitPrice(nt.getUnitPrice()).grossAmount(nt.getGrossAmount())
                    .fees(nt.getFees()).taxes(nt.getTaxes()).netAmount(nt.getNetAmount())
                    .currency(nt.getCurrency() == null ? "INR" : nt.getCurrency())
                    .ratioFrom(nt.getRatioFrom()).ratioTo(nt.getRatioTo())
                    .newSymbol(nt.getNewSymbol()).interestRate(nt.getInterestRate()).maturityDate(nt.getMaturityDate())
                    .sourceType(nt.getSourceType()).sourceProvider(nt.getSourceProvider()).sourceReference(nt.getSourceReference())
                    .sourceTimestamp(nt.getSourceTimestamp()).ingestedAt(now).confidence(0)
                    .status(nt.isReversal() ? TxnStatus.REVERSED
                        : nt.getSourceType().authoritative() ? TxnStatus.CONFIRMED : TxnStatus.PENDING_RECONCILIATION)
                    .reconciliationStatus(ReconStatus.PENDING).linkGroup(nt.getLinkGroup()).notes(nt.getNotes()).build());
                sources.save(newSource(entry.getId(), raw.getId(), nt, "NEW", explanation));
                audit.record(entry.getUserId(), AuditService.SYSTEM, "TRANSACTION", entry.getId(), "CREATED", null,
                    nt.getType() + " from " + nt.getSourceType(), explanation);
                events.emit(IngestionEvents.TRANSACTION_CREATED, "transactionId", entry.getId(), "userId", entry.getUserId(),
                    "type", nt.getType(), "source", nt.getSourceType());
                outcome = Outcome.CREATED;
                if (d.possibleDuplicate() || d.kind() == TransactionMatcher.Kind.AMBIGUOUS) possibleDuplicate(entry, d);
                else if (nt.getSourceType().authoritative() && !nt.isReversal()) detectMissing(entry, coverage);
            }
        }
        entry = recompute(entry, now);
        return new Applied(outcome, entry, previousAccountId);
    }

    private void possibleDuplicate(CanonicalTransaction entry, TransactionMatcher.Decision d) {
        Long other = d.relatedIds().isEmpty() ? null : d.relatedIds().get(0);
        issues.raise(new IssueService.Raise(entry.getUserId(), entry.getAccountId(), entry.getAssetId(), IssueType.POSSIBLE_DUPLICATE,
            IssueSeverity.MEDIUM, "DUP:" + entry.getId(), "Possible duplicate transaction",
            d.explanationText() + ". Confirm if they are two separate transactions, or merge them.",
            null, null, null, List.of(SuspectedCause.DUPLICATE_TRANSACTION), entry.getId(), other));
    }

    /**
     * A transaction an authoritative source reports that the ledger lacks, where the ledger has
     * weaker-source history around it (entries before and after, or anywhere inside the feed's
     * coverage window), is a missing transaction: surfaced, not silently absorbed.
     */
    private void detectMissing(CanonicalTransaction entry, Coverage coverage) {
        if (entry.getAssetId() == null) return;
        List<CanonicalTransaction> weak = txns.findByUserIdAndAssetIdAndTransactionDateBetween(
                entry.getUserId(), entry.getAssetId(), LocalDate.of(1990, 1, 1), LocalDate.of(2100, 1, 1)).stream()
            .filter(t -> !t.getId().equals(entry.getId()) && t.getStatus().counts() && hasWeakSource(t)).toList();
        if (weak.isEmpty()) return;
        LocalDate d = entry.getTransactionDate();
        boolean before = weak.stream().anyMatch(t -> t.getTransactionDate().isBefore(d));
        boolean after = weak.stream().anyMatch(t -> t.getTransactionDate().isAfter(d));
        boolean inWindow = coverage != null && coverage.from() != null && coverage.to() != null
            && weak.stream().anyMatch(t -> !t.getTransactionDate().isBefore(coverage.from()) && !t.getTransactionDate().isAfter(coverage.to()));
        if (!((before && after) || inWindow)) return;
        entry.setReconciliationStatus(ReconStatus.MISSING);
        issues.raise(new IssueService.Raise(entry.getUserId(), entry.getAccountId(), entry.getAssetId(), IssueType.MISSING_TRANSACTION,
            IssueSeverity.MEDIUM, "MISSING:" + entry.getId(), "Transaction missing from your records",
            entry.getTransactionType() + (entry.getNetAmount() != null ? " of ₹" + entry.getNetAmount().toPlainString() : "")
                + " on " + d + " is reported by " + entry.getSourceType() + " but was not in Wealth-OS, which has "
                + weak.size() + " other record(s) for this asset from weaker sources. It has been added to the ledger and needs your review.",
            null, null, null, List.of(SuspectedCause.MISSING_TRANSACTION), entry.getId(), null));
        events.emit(IngestionEvents.MISSING_TRANSACTION, "transactionId", entry.getId(), "userId", entry.getUserId());
    }

    /**
     * Weaker-source history: an entry some email/manual/AI source reported, even if an institution
     * has since corroborated it. Checking only the lead source would let a batch erase the very
     * evidence ("the email list has June, July and September") that makes a gap visible.
     */
    private boolean hasWeakSource(CanonicalTransaction t) {
        if (!t.getSourceType().authoritative()) return true;
        return sources.findByTransactionIdOrderByIdAsc(t.getId()).stream().anyMatch(s -> !s.getSourceType().authoritative());
    }

    /* ───────────── derive state from sources ───────────── */

    public CanonicalTransaction recompute(CanonicalTransaction entry, LocalDateTime now) {
        List<TransactionSource> srcs = sources.findByTransactionIdOrderByIdAsc(entry.getId());
        List<SourceObservation> obs = srcs.stream().map(LedgerService::observation).toList();
        if (obs.isEmpty()) return entry;
        ReconciliationEngine.Consolidated c = engine.consolidate(obs);
        SourceObservation lead = c.lead();
        if (c.date() != null) entry.setTransactionDate(c.date());
        entry.setQuantity(c.quantity()); entry.setUnitPrice(c.unitPrice()); entry.setGrossAmount(c.gross());
        entry.setFees(c.fees()); entry.setTaxes(c.taxes()); entry.setNetAmount(c.net());
        entry.setSourceType(lead.type()); entry.setSourceProvider(lead.provider());
        entry.setSourceReference(lead.reference()); entry.setSourceTimestamp(lead.timestamp());

        Evaluation ev = engine.evaluate(obs, entry.getIngestedAt(), now, Duration.ofDays(graceDays));
        ReconStatus previous = entry.getReconciliationStatus();
        ReconStatus next = ev.status();
        boolean userConfirmed = entry.getStatus() == TxnStatus.CONFIRMED && !lead.type().authoritative();
        if (userConfirmed && (next == ReconStatus.PENDING || next == ReconStatus.UNCONFIRMED)) next = ReconStatus.MATCHED;
        if (previous == ReconStatus.MISSING && issues.hasOpen(entry.getUserId(), IssueType.MISSING_TRANSACTION, entry.getId())) next = ReconStatus.MISSING;
        if (lead.type().authoritative() && entry.getStatus() == TxnStatus.PENDING_RECONCILIATION) entry.setStatus(TxnStatus.CONFIRMED);
        entry.setReconciliationStatus(next);
        if (next == ReconStatus.VERIFIED) entry.setLastVerifiedAt(now);
        entry.setConfidence(confidence.confidence(
            obs.stream().map(o -> new Observation(o.type(), o.recordConfidence())).toList(), ev.sourcesDisagree()));
        entry = txns.save(entry);

        if (previous != next) {
            events.emit(IngestionEvents.TRANSACTION_RECONCILED, "transactionId", entry.getId(), "from", previous, "to", next);
        }
        syncIssues(entry, ev, obs);
        return entry;
    }

    private void syncIssues(CanonicalTransaction e, Evaluation ev, List<SourceObservation> obs) {
        Long u = e.getUserId();
        if (e.getReconciliationStatus() == ReconStatus.CONFLICT) {
            issues.raise(new IssueService.Raise(u, e.getAccountId(), e.getAssetId(), IssueType.CONFLICTING_SOURCES, IssueSeverity.HIGH,
                "CONFLICT:" + e.getId(), "Sources disagree about a transaction",
                "Sources report different figures for this transaction and none can settle it: "
                    + obs.stream().map(o -> o.type() + " " + figures(o)).collect(Collectors.joining("; ")) + ".",
                null, null, null, List.of(SuspectedCause.SOURCE_ERROR), e.getId(), null));
            events.emit(IngestionEvents.TRANSACTION_CONFLICT, "transactionId", e.getId(), "userId", u);
        } else {
            issues.autoResolve(u, "CONFLICT:" + e.getId(), "Sources no longer disagree (" + e.getReconciliationStatus() + ")");
        }
        if (ev.authorityOverrides()) {
            issues.recordAutoResolved(new IssueService.Raise(u, e.getAccountId(), e.getAssetId(), IssueType.CONFLICTING_SOURCES, IssueSeverity.LOW,
                "OVERRIDE:" + e.getId(), "A weaker source disagreed; the institution's figures were used",
                obs.stream().map(o -> o.type() + " " + figures(o)).collect(Collectors.joining("; ")) + ".",
                null, null, null, List.of(SuspectedCause.SOURCE_ERROR), e.getId(), null),
                "The figures from " + e.getSourceType() + ", an authoritative source, were used.");
        }
        if (e.getReconciliationStatus() == ReconStatus.UNCONFIRMED) {
            if (e.getTransactionDate().isBefore(LocalDate.now().minusDays(issueWindowDays))) return;
            issues.raise(new IssueService.Raise(u, e.getAccountId(), e.getAssetId(), IssueType.UNCONFIRMED_TRANSACTION, IssueSeverity.MEDIUM,
                "UNCONFIRMED:" + e.getId(), "Transaction not confirmed by any institution source",
                e.getTransactionType() + (e.getNetAmount() != null ? " of ₹" + e.getNetAmount().toPlainString() : "") + " on "
                    + e.getTransactionDate() + " is known only from " + e.getSourceType()
                    + " and has not been corroborated within " + graceDays + " days.",
                null, null, null, List.of(), e.getId(), null));
        } else {
            issues.autoResolve(u, "UNCONFIRMED:" + e.getId(), "Now " + e.getReconciliationStatus());
        }
    }

    /**
     * Entries in an account window that an authoritative feed covering that window did not list
     * and that no authoritative source backs are UNCONFIRMED immediately: the institution was
     * asked and did not report them.
     */
    public int markUnlistedUnconfirmed(Collection<Long> accountIds, Coverage coverage, Set<Long> listedEntryIds, String feedName) {
        int n = 0;
        // A feed for a specific account also speaks for placeholder-account entries at that institution.
        Set<Long> scope = new java.util.LinkedHashSet<>();
        for (Long accountId : accountIds) {
            accounts.findById(accountId).ifPresentOrElse(a -> scope.addAll(accountResolver.scopeFor(a)), () -> scope.add(accountId));
        }
        for (Long accountId : scope) {
            for (CanonicalTransaction t : txns.findByAccountIdAndTransactionDateBetweenAndStatusIn(accountId, coverage.from(), coverage.to(), COUNTING)) {
                if (listedEntryIds.contains(t.getId()) || t.getSourceType().authoritative()) continue;
                if (t.getReconciliationStatus() == ReconStatus.UNCONFIRMED || t.getReconciliationStatus() == ReconStatus.MISSING) continue;
                if (t.getStatus() == TxnStatus.CONFIRMED) continue;   // a person already confirmed it
                t.setReconciliationStatus(ReconStatus.UNCONFIRMED);
                txns.save(t);
                issues.raise(new IssueService.Raise(t.getUserId(), t.getAccountId(), t.getAssetId(), IssueType.UNCONFIRMED_TRANSACTION,
                    IssueSeverity.MEDIUM, "UNCONFIRMED:" + t.getId(), "Transaction not listed by the institution",
                    t.getTransactionType() + " on " + t.getTransactionDate() + " is known from " + t.getSourceType() + " but "
                        + feedName + " does not list it for " + coverage.from() + " to " + coverage.to() + ".",
                    null, null, null, List.of(SuspectedCause.SOURCE_ERROR, SuspectedCause.DATA_DELAY), t.getId(), null));
                events.emit(IngestionEvents.TRANSACTION_RECONCILED, "transactionId", t.getId(), "to", ReconStatus.UNCONFIRMED);
                n++;
            }
        }
        return n;
    }

    /** Moves single weak reports past their grace period to UNCONFIRMED. Returns how many changed. */
    public int sweepGracePeriods(LocalDateTime now) {
        int n = 0;
        for (CanonicalTransaction t : txns.findByReconciliationStatusAndIngestedAtBefore(ReconStatus.PENDING, now.minusDays(graceDays))) {
            if (!t.getStatus().counts()) continue;
            CanonicalTransaction after = recompute(t, now);
            if (after.getReconciliationStatus() != ReconStatus.PENDING) n++;
        }
        return n;
    }

    /* ───────────── helpers ───────────── */

    static SourceObservation observation(TransactionSource s) {
        return new SourceObservation(s.getSourceType(), s.getSourceProvider(), s.getSourceReference(), s.getSourceTimestamp(),
            s.getReportedDate(), s.getReportedQuantity(), s.getReportedUnitPrice(), s.getReportedGrossAmount(),
            null, null, s.getReportedNetAmount(), s.getRecordConfidence());
    }

    private static TransactionSource newSource(Long txnId, Long rawId, NormalizedTransaction nt, String kind, String explanation) {
        TransactionSource s = TransactionSource.builder().transactionId(txnId).rawRecordId(rawId).build();
        copyFigures(s, nt, rawId, kind, explanation);
        return s;
    }

    private static TransactionSource copyFigures(TransactionSource s, NormalizedTransaction nt, Long rawId, String kind, String explanation) {
        s.setRawRecordId(rawId);
        s.setSourceType(nt.getSourceType()); s.setSourceProvider(nt.getSourceProvider());
        s.setSourceReference(nt.getSourceReference()); s.setSourceTimestamp(nt.getSourceTimestamp());
        s.setReportedType(nt.getType().name()); s.setReportedDate(nt.getTransactionDate());
        s.setReportedQuantity(nt.getQuantity()); s.setReportedUnitPrice(nt.getUnitPrice());
        s.setReportedGrossAmount(nt.getGrossAmount()); s.setReportedNetAmount(nt.getNetAmount());
        s.setRecordConfidence(nt.getRecordConfidence());
        s.setMatchKind(kind);
        s.setMatchExplanation(explanation == null ? null : explanation.length() > 1000 ? explanation.substring(0, 1000) : explanation);
        return s;
    }

    private static String figures(SourceObservation o) {
        BigDecimal amt = o.net() != null ? o.net() : o.gross();
        return (o.quantity() != null ? o.quantity().stripTrailingZeros().toPlainString() + " units " : "")
            + (amt != null ? "₹" + amt.toPlainString() : "");
    }

    private static boolean eqi(String a, String b) { return a == null ? b == null : a.equalsIgnoreCase(b); }
}
