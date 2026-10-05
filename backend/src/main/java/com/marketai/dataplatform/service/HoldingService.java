package com.marketai.dataplatform.service;

import com.marketai.dataplatform.domain.*;
import com.marketai.dataplatform.pipeline.*;
import com.marketai.dataplatform.repo.*;
import com.marketai.portfolio.util.XirrCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Holding snapshots and their reconciliation. For every (account, asset) two snapshots are kept:
 * what the ledger implies, and what the institution reports. The institution's figure is the
 * verification source for the current position; a difference is raised as an issue and is never
 * hidden by changing either side.
 */
@Service
@RequiredArgsConstructor
public class HoldingService {

    private static final List<TxnStatus> COUNTING = List.of(TxnStatus.PENDING_RECONCILIATION, TxnStatus.CONFIRMED);

    private final CanonicalTransactionRepository txns;
    private final HoldingSnapshotRepository snapshots;
    private final FinancialAccountRepository accounts;
    private final FinancialAssetRepository assets;
    private final AccountResolver accountResolver;
    private final AssetResolver assetResolver;
    private final HoldingReconciler reconciler;
    private final IssueService issues;
    private final IngestionEvents events;

    /** Stores an institution-reported holding and reconciles the ledger against it. */
    public HoldingSnapshot ingestReported(Long userId, NormalizedHolding h, Long rawRecordId) {
        AccountResolver.Resolved acct = accountResolver.resolve(userId, h.getAccount());
        FinancialAsset asset = assetResolver.resolve(h.getAsset());
        LocalDateTime now = LocalDateTime.now();
        HoldingSnapshot s = snapshots.findByAccountIdAndAssetIdAndBasis(acct.account().getId(), asset.getId(), BasisType.INSTITUTION_REPORTED)
            .orElseGet(() -> HoldingSnapshot.builder().userId(userId).familyId(acct.account().getFamilyId())
                .accountId(acct.account().getId()).assetId(asset.getId()).basis(BasisType.INSTITUTION_REPORTED).build());
        // An older report never replaces a newer one.
        if (s.getAsOfDate() != null && h.getAsOfDate() != null && h.getAsOfDate().isBefore(s.getAsOfDate())) return s;
        s.setQuantity(h.getQuantity()); s.setAverageCost(h.getAverageCost()); s.setInvestedValue(h.getInvestedValue());
        s.setCurrentPrice(h.getCurrentPrice());
        BigDecimal value = h.getCurrentValue() != null ? h.getCurrentValue()
            : h.getCurrentPrice() != null && h.getQuantity() != null ? h.getCurrentPrice().multiply(h.getQuantity()).setScale(2, RoundingMode.HALF_UP) : null;
        s.setCurrentValue(value);
        if (value != null && h.getInvestedValue() != null && h.getInvestedValue().signum() > 0) {
            s.setUnrealizedPnl(value.subtract(h.getInvestedValue()));
            s.setAbsoluteReturn(value.subtract(h.getInvestedValue()).divide(h.getInvestedValue(), 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP));
        }
        s.setXirr(h.getXirr());
        s.setAsOfDate(h.getAsOfDate() == null ? now.toLocalDate() : h.getAsOfDate());
        s.setLastVerifiedAt(now);
        s.setSourceType(h.getSourceType()); s.setSourceProvider(h.getSourceProvider());
        s.setConfidence(h.getSourceType().baseConfidence() * Math.max(0, Math.min(1, h.getRecordConfidence())));
        s.setRawRecordId(rawRecordId);
        s = snapshots.save(s);
        refresh(userId, acct.account().getId(), asset.getId());
        return s;
    }

    /** Recomputes the ledger-implied snapshot for one (account, asset) and reconciles it with the reported one. */
    public void refresh(Long userId, Long accountId, Long assetId) {
        FinancialAccount account = accounts.findById(accountId).orElseThrow();
        FinancialAsset asset = assets.findById(assetId).orElseThrow();
        List<CanonicalTransaction> entries = entriesFor(account, assetId);
        boolean anyEntryAtAll = !txns.findByAccountIdAndAssetIdAndStatusIn(accountId, assetId, java.util.List.of(TxnStatus.values())).isEmpty();
        if (entries.isEmpty() && !anyEntryAtAll) {
            // Nothing is recorded here any more (e.g. its entries were attributed to a specific account):
            // a calculated snapshot would be a ghost holding. An institution-reported one is kept as reported.
            snapshots.findByAccountIdAndAssetIdAndBasis(accountId, assetId, BasisType.LEDGER_CALCULATED).ifPresent(snapshots::delete);
            if (snapshots.findByAccountIdAndAssetIdAndBasis(accountId, assetId, BasisType.INSTITUTION_REPORTED).isEmpty()) return;
        }
        LedgerReplay.Position p = LedgerReplay.replay(entries.stream().map(HoldingService::replayEntry).toList());

        HoldingSnapshot calc = snapshots.findByAccountIdAndAssetIdAndBasis(accountId, assetId, BasisType.LEDGER_CALCULATED)
            .orElseGet(() -> HoldingSnapshot.builder().userId(userId).familyId(account.getFamilyId())
                .accountId(accountId).assetId(assetId).basis(BasisType.LEDGER_CALCULATED).build());
        Optional<HoldingSnapshot> reported = snapshots.findByAccountIdAndAssetIdAndBasis(accountId, assetId, BasisType.INSTITUTION_REPORTED);
        BigDecimal price = reported.map(HoldingSnapshot::getCurrentPrice).orElse(null);
        calc.setQuantity(p.quantity()); calc.setAverageCost(p.averageCost()); calc.setInvestedValue(p.investedValue());
        calc.setRealizedPnl(p.realizedPnl());
        calc.setCurrentPrice(price);
        calc.setCurrentValue(price == null ? null : price.multiply(p.quantity()).setScale(2, RoundingMode.HALF_UP));
        calc.setUnrealizedPnl(calc.getCurrentValue() == null ? null : calc.getCurrentValue().subtract(p.investedValue()));
        calc.setAbsoluteReturn(calc.getCurrentValue() == null || p.investedValue().signum() <= 0 ? null
            : calc.getCurrentValue().subtract(p.investedValue()).divide(p.investedValue(), 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP));
        calc.setXirr(xirr(entries, calc.getCurrentValue(), p.quantity()));
        calc.setAsOfDate(p.lastActivity());
        entries.stream().min(Comparator.comparingInt(e -> e.getSourceType().rank())).ifPresent(e -> {
            calc.setSourceType(e.getSourceType()); calc.setSourceProvider(e.getSourceProvider());
        });
        calc.setConfidence(entries.stream().mapToDouble(CanonicalTransaction::getConfidence).min().orElse(0));
        calc.setLastVerifiedAt(reported.map(HoldingSnapshot::getLastVerifiedAt).orElse(null));
        snapshots.save(calc);

        reconcile(userId, account, asset, p, entries, reported.orElse(null));
    }

    private void reconcile(Long userId, FinancialAccount account, FinancialAsset asset, LedgerReplay.Position p,
                           List<CanonicalTransaction> entries, HoldingSnapshot reported) {
        String key = "HOLDING:" + account.getId() + ":" + asset.getId();
        if (reported == null || reported.getQuantity() == null) return;
        HoldingReconciler.Result r = reconciler.compare(p.quantity(), reported.getQuantity(), evidence(entries, p, reported));
        if (r.matches()) {
            issues.autoResolve(userId, key, "The ledger now matches the institution's reported holding of " + reported.getQuantity().stripTrailingZeros().toPlainString() + ".");
            return;
        }
        String name = asset.getName() != null ? asset.getName() : asset.getAssetKey();
        issues.raise(new IssueService.Raise(userId, account.getId(), asset.getId(), IssueType.HOLDING_MISMATCH, r.severity(), key,
            "Holding mismatch: " + name,
            "Wealth-OS calculates " + p.quantity().stripTrailingZeros().toPlainString() + " but " + reported.getSourceType()
                + (reported.getSourceProvider() == null ? "" : " (" + reported.getSourceProvider() + ")") + " reports "
                + reported.getQuantity().stripTrailingZeros().toPlainString() + " as of " + reported.getAsOfDate() + ". The ledger has not been changed.",
            p.quantity().stripTrailingZeros().toPlainString(), reported.getQuantity().stripTrailingZeros().toPlainString(),
            r.difference(), r.causes(), null, null));
        events.emit(IngestionEvents.HOLDING_MISMATCH, "accountId", account.getId(), "assetId", asset.getId(), "difference", r.difference().toPlainString());
    }

    /** The entries that make up one account's position for an asset. */
    List<CanonicalTransaction> entriesFor(FinancialAccount account, Long assetId) {
        List<CanonicalTransaction> all = new ArrayList<>();
        for (Long id : accountResolver.scopeFor(account)) all.addAll(txns.findByAccountIdAndAssetIdAndStatusIn(id, assetId, COUNTING));
        return all;
    }

    private static LedgerReplay.Entry replayEntry(CanonicalTransaction t) {
        return new LedgerReplay.Entry(t.getId(), t.getTransactionType(), t.getTransactionDate(), t.getQuantity(), t.getUnitPrice(),
            t.getGrossAmount(), t.getFees(), t.getRatioFrom(), t.getRatioTo());
    }

    private HoldingReconciler.Evidence evidence(List<CanonicalTransaction> entries, LedgerReplay.Position p, HoldingSnapshot reported) {
        List<BigDecimal> unconfirmed = entries.stream()
            .filter(e -> (e.getReconciliationStatus() == ReconStatus.UNCONFIRMED || e.getReconciliationStatus() == ReconStatus.PENDING) && e.getQuantity() != null)
            .map(CanonicalTransaction::getQuantity).toList();
        List<BigDecimal> dup = entries.stream().filter(e -> e.getQuantity() != null
                && issues.hasOpen(e.getUserId(), IssueType.POSSIBLE_DUPLICATE, e.getId()))
            .map(CanonicalTransaction::getQuantity).toList();
        boolean switches = entries.stream().anyMatch(e -> e.getTransactionType() == TransactionType.SWITCH_IN || e.getTransactionType() == TransactionType.SWITCH_OUT
            || e.getTransactionType() == TransactionType.STP || e.getTransactionType() == TransactionType.SWP);
        boolean reinvest = entries.stream().anyMatch(e -> e.getNotes() != null && e.getNotes().toLowerCase().contains("reinvest"));
        boolean ca = entries.stream().anyMatch(e -> e.getTransactionType().family() == TransactionType.Family.CORPORATE_ACTION);
        boolean manual = entries.stream().anyMatch(e -> e.getSourceType() == SourceType.MANUAL);
        return new HoldingReconciler.Evidence(unconfirmed, dup, switches, reinvest, ca, manual, p.lastActivity(), reported.getAsOfDate(),
            p.incompleteEntries() > 0);
    }

    private static Double toD(BigDecimal b) { return b == null ? null : b.doubleValue(); }

    /** XIRR of the cash flows plus the current value as a final inflow; null when it cannot be solved. */
    private BigDecimal xirr(List<CanonicalTransaction> entries, BigDecimal currentValue, BigDecimal qty) {
        List<XirrCalculator.CashFlow> flows = new ArrayList<>();
        for (CanonicalTransaction t : entries) {
            if (!t.getTransactionType().movesUnits() || t.getQuantity() == null) continue;
            BigDecimal amt = t.getNetAmount() != null ? t.getNetAmount() : t.getGrossAmount();
            if (amt == null) continue;
            flows.add(new XirrCalculator.CashFlow(t.getTransactionDate(), t.getTransactionType().unitEffect() > 0 ? amt.negate() : amt));
        }
        if (flows.isEmpty() || currentValue == null || qty.signum() <= 0) return null;
        flows.add(new XirrCalculator.CashFlow(LocalDate.now(), currentValue));
        try {
            Double v = XirrCalculator.computeXirrPercent(flows);
            return v == null || v.isNaN() || v.isInfinite() ? null : BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP);
        } catch (RuntimeException e) { return null; }
    }

    public List<HoldingSnapshot> forAccounts(Collection<Long> accountIds) {
        return accountIds.isEmpty() ? List.of() : snapshots.findByAccountIdIn(accountIds);
    }

    public Map<String, List<HoldingSnapshot>> byAssetKey(Collection<Long> accountIds) {
        Map<Long, FinancialAsset> m = assets.findAllById(forAccounts(accountIds).stream().map(HoldingSnapshot::getAssetId).distinct().toList())
            .stream().collect(Collectors.toMap(FinancialAsset::getId, a -> a));
        Map<String, List<HoldingSnapshot>> out = new HashMap<>();
        for (HoldingSnapshot s : forAccounts(accountIds)) {
            FinancialAsset a = m.get(s.getAssetId());
            if (a != null) out.computeIfAbsent(a.getAssetKey(), k -> new ArrayList<>()).add(s);
        }
        return out;
    }
}
