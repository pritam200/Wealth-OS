package com.marketai.dataplatform.service;

import com.marketai.dataplatform.domain.*;
import com.marketai.dataplatform.repo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** Read side of the ledger: transaction lists and the full audit view of one transaction. */
@Service
@RequiredArgsConstructor
public class TransactionQueryService {

    private static final List<TxnStatus> ALL_STATUS = List.of(TxnStatus.values());

    private final FamilyAccessService access;
    private final CanonicalTransactionRepository txns;
    private final TransactionSourceRepository sources;
    private final FinancialAccountRepository accounts;
    private final FinancialAssetRepository assets;
    private final LedgerIssueRepository issueRepo;
    private final AuditService audit;

    public record Row(Long id, Long accountId, String account, Long assetId, String asset, TransactionType type, LocalDate date,
                      BigDecimal quantity, BigDecimal unitPrice, BigDecimal netAmount, TxnStatus status,
                      ReconStatus reconciliationStatus, double confidence, SourceType sourceType, int sourceCount) {}

    public record SourceView(SourceType sourceType, String provider, String reference, LocalDateTime timestamp, String reportedType,
                             LocalDate reportedDate, BigDecimal quantity, BigDecimal unitPrice, BigDecimal grossAmount, BigDecimal netAmount,
                             double recordConfidence, String matchKind, String matchExplanation, LocalDateTime linkedAt) {}

    public record IssueView(Long id, IssueType type, IssueSeverity severity, IssueStatus status, String title, String description) {}

    public record AuditView(String actor, String action, String before, String after, String note, LocalDateTime at) {}

    public record Detail(Row summary, String assetSymbol, String assetIsin, String accountInstitution, Ownership ownership,
                         BigDecimal grossAmount, BigDecimal fees, BigDecimal taxes, String currency, LocalDate settlementDate,
                         List<SourceView> sources, List<String> externalReferences, List<IssueView> issues, List<AuditView> history,
                         LocalDateTime createdAt, LocalDateTime lastVerifiedAt, LocalDateTime ingestedAt, String notes, String confidenceExplanation) {}

    public List<Row> list(Long userId, boolean includeFamily, Long assetId, Long accountId, int limit) {
        Set<Long> ids = access.accessibleAccountIds(userId, includeFamily);
        if (accountId != null) ids = ids.contains(accountId) ? Set.of(accountId) : Set.of();
        if (ids.isEmpty()) return List.of();
        List<CanonicalTransaction> list = txns.findByAccountIdInAndStatusInOrderByTransactionDateDescIdDesc(ids, ALL_STATUS).stream()
            .filter(t -> assetId == null || assetId.equals(t.getAssetId())).limit(Math.max(1, Math.min(limit, 500))).toList();
        return rows(list);
    }

    private List<Row> rows(List<CanonicalTransaction> list) {
        Map<Long, FinancialAccount> a = accounts.findAllById(list.stream().map(CanonicalTransaction::getAccountId).distinct().toList())
            .stream().collect(Collectors.toMap(FinancialAccount::getId, x -> x));
        Map<Long, FinancialAsset> s = assets.findAllById(list.stream().map(CanonicalTransaction::getAssetId).filter(Objects::nonNull).distinct().toList())
            .stream().collect(Collectors.toMap(FinancialAsset::getId, x -> x));
        Map<Long, Long> counts = sources.findByTransactionIdIn(list.stream().map(CanonicalTransaction::getId).toList()).stream()
            .collect(Collectors.groupingBy(TransactionSource::getTransactionId, Collectors.counting()));
        return list.stream().map(t -> new Row(t.getId(), t.getAccountId(), a.get(t.getAccountId()) == null ? null : a.get(t.getAccountId()).getDisplayName(),
            t.getAssetId(), t.getAssetId() == null || s.get(t.getAssetId()) == null ? null
                : s.get(t.getAssetId()).getName() != null ? s.get(t.getAssetId()).getName() : s.get(t.getAssetId()).getAssetKey(),
            t.getTransactionType(), t.getTransactionDate(), t.getQuantity(), t.getUnitPrice(), t.getNetAmount(), t.getStatus(),
            t.getReconciliationStatus(), t.getConfidence(), t.getSourceType(), counts.getOrDefault(t.getId(), 0L).intValue())).toList();
    }

    public Detail detail(Long userId, Long id) {
        CanonicalTransaction t = txns.findById(id).filter(x -> access.canAccess(userId, x.getAccountId()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found"));
        FinancialAccount acct = accounts.findById(t.getAccountId()).orElse(null);
        FinancialAsset asset = t.getAssetId() == null ? null : assets.findById(t.getAssetId()).orElse(null);
        List<TransactionSource> srcs = sources.findByTransactionIdOrderByIdAsc(t.getId());
        List<SourceView> views = srcs.stream().map(s -> new SourceView(s.getSourceType(), s.getSourceProvider(), s.getSourceReference(),
            s.getSourceTimestamp(), s.getReportedType(), s.getReportedDate(), s.getReportedQuantity(), s.getReportedUnitPrice(),
            s.getReportedGrossAmount(), s.getReportedNetAmount(), s.getRecordConfidence(), s.getMatchKind(), s.getMatchExplanation(), s.getLinkedAt())).toList();
        List<String> refs = srcs.stream().map(TransactionSource::getSourceReference).filter(r -> r != null && !r.isBlank()).distinct().toList();
        List<IssueView> issues = issueRepo.findByTransactionIdOrOtherTransactionId(t.getId(), t.getId()).stream()
            .map(i -> new IssueView(i.getId(), i.getType(), i.getSeverity(), i.getStatus(), i.getTitle(), i.getDescription())).toList();
        List<AuditView> history = audit.history("TRANSACTION", t.getId()).stream()
            .map(e -> new AuditView(e.getActor(), e.getAction(), e.getBefore(), e.getAfter(), e.getNote(), e.getOccurredAt())).toList();
        Row row = rows(List.of(t)).get(0);
        return new Detail(row, asset == null ? null : asset.getSymbol(), asset == null ? null : asset.getIsin(),
            acct == null ? null : acct.getInstitution(), acct == null ? null : acct.getOwnership(),
            t.getGrossAmount(), t.getFees(), t.getTaxes(), t.getCurrency(), t.getSettlementDate(), views, refs, issues, history,
            t.getCreatedAt(), t.getLastVerifiedAt(), t.getIngestedAt(), t.getNotes(), explainConfidence(srcs, t));
    }

    private static String explainConfidence(List<TransactionSource> srcs, CanonicalTransaction t) {
        String parts = srcs.stream().map(s -> s.getSourceType() + " " + String.format("%.2f", s.getSourceType().baseConfidence() * s.getRecordConfidence()))
            .collect(Collectors.joining(", "));
        return "Best source × record quality (" + parts + "), +0.05 per further independent source type"
            + (t.getReconciliationStatus() == ReconStatus.CONFLICT ? "; capped at 0.5 because sources disagree" : "") + ".";
    }
}
