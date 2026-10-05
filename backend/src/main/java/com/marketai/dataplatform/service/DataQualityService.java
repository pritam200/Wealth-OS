package com.marketai.dataplatform.service;

import com.marketai.dataplatform.domain.*;
import com.marketai.dataplatform.pipeline.DataQualityScorer;
import com.marketai.dataplatform.repo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Portfolio data health, from the stored records and nothing else. The score is
 * {@link DataQualityScorer}'s deterministic ratio; this service gathers its inputs and the
 * per-account and per-holding breakdowns the UI shows.
 */
@Service
@RequiredArgsConstructor
public class DataQualityService {

    private static final List<TxnStatus> COUNTING = List.of(TxnStatus.PENDING_RECONCILIATION, TxnStatus.CONFIRMED);
    private static final List<IssueStatus> OPEN = List.of(IssueStatus.OPEN, IssueStatus.IN_REVIEW);

    private final FamilyAccessService access;
    private final FinancialAccountRepository accounts;
    private final FinancialAssetRepository assets;
    private final CanonicalTransactionRepository txns;
    private final HoldingSnapshotRepository snapshots;
    private final LedgerIssueRepository issueRepo;
    private final TransactionCandidateRepository candidates;
    private final DataQualityScorer scorer;

    public record AccountHealth(Long accountId, String name, String institution, Ownership ownership, Double scorePercent,
                                long transactions, long verified, long unverified) {}

    public record HoldingHealth(Long accountId, Long assetId, String assetName, String symbol, String isin,
                                BigDecimal calculatedQuantity, BigDecimal reportedQuantity, String state, String source,
                                LocalDate asOf, LocalDateTime lastVerifiedAt, Long openIssueId) {}

    public record Health(Double scorePercent, String formula, Map<String, Long> counts, long considered,
                         long verified, long pending, long unconfirmed, long missing, long conflicts,
                         long possibleDuplicates, long duplicatesPrevented, long holdingMismatches, long awaitingConfirmation,
                         Map<String, Long> bySource, boolean hasAuthoritativeSource, LocalDateTime lastVerifiedAt,
                         List<AccountHealth> accounts, List<HoldingHealth> holdings, List<String> warnings) {}

    public Health health(Long userId, boolean includeFamily) {
        Set<Long> accountIds = access.accessibleAccountIds(userId, includeFamily);
        if (accountIds.isEmpty()) return empty();

        Map<ReconStatus, Long> counts = new EnumMap<>(ReconStatus.class);
        for (Object[] r : txns.countByReconciliation(accountIds, COUNTING)) counts.put((ReconStatus) r[0], ((Number) r[1]).longValue());
        DataQualityScorer.Score score = scorer.score(counts, 0);

        List<CanonicalTransaction> all = txns.findByAccountIdInAndStatusInOrderByTransactionDateDescIdDesc(accountIds, COUNTING);
        Map<String, Long> bySource = all.stream().collect(Collectors.groupingBy(t -> t.getSourceType().name(), TreeMap::new, Collectors.counting()));
        boolean authoritative = all.stream().anyMatch(t -> t.getSourceType().authoritative());
        LocalDateTime lastVerified = all.stream().map(CanonicalTransaction::getLastVerifiedAt).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);

        List<LedgerIssue> openIssues = issueRepo.findByAccountIdInAndStatusInOrderByLastSeenAtDesc(accountIds, OPEN);
        long mismatches = openIssues.stream().filter(i -> i.getType() == IssueType.HOLDING_MISMATCH).count();
        long dups = openIssues.stream().filter(i -> i.getType() == IssueType.POSSIBLE_DUPLICATE).count();
        long prevented = candidates.countByUserIdAndStatus(userId, CandidateStatus.MATCHED_EXISTING) + candidates.countByUserIdAndStatus(userId, CandidateStatus.DUPLICATE);

        List<String> warnings = new ArrayList<>();
        if (!all.isEmpty() && !authoritative)
            warnings.add("Every transaction comes from email, statements you entered or manual entry. No institution source is connected, so nothing is verified yet.");
        if (mismatches > 0) warnings.add(mismatches + " holding(s) differ from what the institution reports.");
        if (counts.getOrDefault(ReconStatus.CONFLICT, 0L) > 0) warnings.add("Some transactions have conflicting sources.");

        return new Health(score.percent(), score.formula(), named(score.counts()), score.considered(),
            c(counts, ReconStatus.VERIFIED), c(counts, ReconStatus.PENDING), c(counts, ReconStatus.UNCONFIRMED), c(counts, ReconStatus.MISSING),
            c(counts, ReconStatus.CONFLICT), dups, prevented, mismatches,
            c(counts, ReconStatus.PENDING) + c(counts, ReconStatus.UNCONFIRMED),
            bySource, authoritative, lastVerified, accountHealth(accountIds), holdingHealth(accountIds, openIssues), warnings);
    }

    List<AccountHealth> accountHealth(Set<Long> accountIds) {
        Map<Long, Map<ReconStatus, Long>> by = new HashMap<>();
        for (Object[] r : txns.countByAccountAndReconciliation(accountIds, COUNTING))
            by.computeIfAbsent((Long) r[0], k -> new EnumMap<>(ReconStatus.class)).put((ReconStatus) r[1], ((Number) r[2]).longValue());
        List<AccountHealth> out = new ArrayList<>();
        for (FinancialAccount a : accounts.findAllById(accountIds)) {
            Map<ReconStatus, Long> m = by.getOrDefault(a.getId(), Map.of());
            // An institution-less placeholder whose entries were attributed elsewhere is bookkeeping, not an account.
            if (m.isEmpty() && AccountResolver.isPlaceholder(a)) continue;
            DataQualityScorer.Score s = scorer.score(m, 0);
            long verified = m.getOrDefault(ReconStatus.VERIFIED, 0L);
            out.add(new AccountHealth(a.getId(), a.getDisplayName(), a.getInstitution(), a.getOwnership(), s.percent(), s.considered(), verified, s.considered() - verified));
        }
        out.sort(Comparator.comparing(AccountHealth::institution, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    List<HoldingHealth> holdingHealth(Set<Long> accountIds, List<LedgerIssue> openIssues) {
        Map<String, LedgerIssue> mismatchByKey = openIssues.stream().filter(i -> i.getType() == IssueType.HOLDING_MISMATCH)
            .collect(Collectors.toMap(i -> i.getAccountId() + ":" + i.getAssetId(), i -> i, (a, b) -> a));
        List<HoldingSnapshot> snaps = snapshots.findByAccountIdIn(accountIds);
        Map<Long, FinancialAsset> assetMap = assets.findAllById(snaps.stream().map(HoldingSnapshot::getAssetId).distinct().toList())
            .stream().collect(Collectors.toMap(FinancialAsset::getId, a -> a));
        Map<String, HoldingSnapshot> calc = new HashMap<>(), rep = new HashMap<>();
        for (HoldingSnapshot s : snaps) (s.getBasis() == BasisType.LEDGER_CALCULATED ? calc : rep).put(s.getAccountId() + ":" + s.getAssetId(), s);
        Set<String> keys = new LinkedHashSet<>(calc.keySet()); keys.addAll(rep.keySet());
        List<HoldingHealth> out = new ArrayList<>();
        for (String k : keys) {
            HoldingSnapshot c = calc.get(k), r = rep.get(k), any = c != null ? c : r;
            if (c != null && c.getQuantity() != null && c.getQuantity().signum() == 0 && r == null) continue;   // fully sold, nothing to verify
            FinancialAsset a = assetMap.get(any.getAssetId());
            LedgerIssue issue = mismatchByKey.get(k);
            String state = issue != null ? "NEEDS_RECONCILIATION" : r != null ? "VERIFIED" : "UNVERIFIED";
            out.add(new HoldingHealth(any.getAccountId(), any.getAssetId(), a == null ? null : a.getName(), a == null ? null : a.getSymbol(),
                a == null ? null : a.getIsin(), c == null ? null : c.getQuantity(), r == null ? null : r.getQuantity(), state,
                r == null ? (c == null || c.getSourceType() == null ? null : "LEDGER (" + c.getSourceType() + ")") : r.getSourceType() + (r.getSourceProvider() == null ? "" : "/" + r.getSourceProvider()),
                r == null ? null : r.getAsOfDate(), r == null ? null : r.getLastVerifiedAt(), issue == null ? null : issue.getId()));
        }
        out.sort(Comparator.comparing((HoldingHealth h) -> h.assetName() == null ? "" : h.assetName(), String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private static long c(Map<ReconStatus, Long> m, ReconStatus s) { return m.getOrDefault(s, 0L); }

    private static Map<String, Long> named(Map<ReconStatus, Long> m) {
        Map<String, Long> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k.name(), v));
        return out;
    }

    private Health empty() {
        return new Health(null, scorer.score(Map.of(), 0).formula(), Map.of(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of(), false, null,
            List.of(), List.of(), List.of("No financial records have been ingested yet."));
    }
}
