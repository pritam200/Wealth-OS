package com.marketai.dataplatform.service;

import com.marketai.dataplatform.domain.*;
import com.marketai.dataplatform.pipeline.*;
import com.marketai.dataplatform.repo.TransactionCandidateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.*;

/**
 * The one route every source takes into the ledger:
 *
 * <pre>
 * raw → validate → normalise → identify (account, asset) → candidate → deduplicate → reconcile
 *     → canonical ledger → holding snapshot
 * </pre>
 *
 * Each stage is its own component; this class only sequences them. Aggregator feeds, broker
 * feeds, statements, CSV imports and email events all enter here, so there is no per-source
 * business logic. Reprocessing the same payload is a no-op, and one bad record never stops the
 * batch.
 */
@Service
@Slf4j
public class IngestionPipeline {

    private final RawDataStore raw;
    private final List<RecordNormalizer> normalizers;
    private final RecordValidator validator;
    private final AccountResolver accounts;
    private final AssetResolver assets;
    private final TransactionMatcher matcher;
    private final LedgerService ledger;
    private final HoldingService holdings;
    private final TransactionCandidateRepository candidates;
    private final IngestionEvents events;
    private final TransactionTemplate tx;

    public IngestionPipeline(RawDataStore raw, List<RecordNormalizer> normalizers, RecordValidator validator,
                             AccountResolver accounts, AssetResolver assets, TransactionMatcher matcher, LedgerService ledger,
                             HoldingService holdings, TransactionCandidateRepository candidates, IngestionEvents events,
                             PlatformTransactionManager txManager) {
        this.raw = raw; this.normalizers = normalizers; this.validator = validator; this.accounts = accounts;
        this.assets = assets; this.matcher = matcher; this.ledger = ledger; this.holdings = holdings;
        this.candidates = candidates; this.events = events; this.tx = new TransactionTemplate(txManager);
    }

    /**
     * @param coverage  what period an authoritative feed covers, so entries it should have listed but did not can be flagged
     */
    public record Request(Long userId, Long connectionId, Long syncRunId, SourceType sourceType, String provider,
                          ProviderMode mode, List<RawRecord> records, LedgerService.Coverage coverage, boolean sameSourceDistinct) {
        public Request(Long userId, Long connectionId, Long syncRunId, SourceType sourceType, String provider,
                       ProviderMode mode, List<RawRecord> records, LedgerService.Coverage coverage) {
            this(userId, connectionId, syncRunId, sourceType, provider, mode, records, coverage, false);
        }
    }

    public static class Result {
        public int fetched, created, updated, duplicated, rejected, reconciled, skipped, failed;
        public final List<String> errors = new ArrayList<>();
        public final Set<Long> touchedEntryIds = new LinkedHashSet<>();
        public final Set<Long> touchedAccountIds = new LinkedHashSet<>();
        public final Set<List<Long>> touchedPositions = new LinkedHashSet<>();
    }

    public Result ingest(Request req) {
        Result res = new Result();
        for (RawRecord rec : req.records()) {
            res.fetched++;
            try {
                process(req, rec, res);
            } catch (Exception e) {
                res.failed++;
                res.rejected++;
                String msg = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
                res.errors.add(msg);
                log.warn("event=record_failed userId={} source={} provider={} error={}", req.userId(), req.sourceType(), req.provider(), msg);
            }
        }
        // Positions are refreshed once per batch, after every record is in the ledger.
        for (List<Long> pos : res.touchedPositions) {
            try { tx.executeWithoutResult(s -> holdings.refresh(req.userId(), pos.get(0), pos.get(1))); }
            catch (Exception e) { res.errors.add("holding refresh failed: " + e.getMessage()); }
        }
        if (req.coverage() != null && req.sourceType().authoritative() && !res.touchedAccountIds.isEmpty()) {
            try {
                res.reconciled += tx.execute(s -> ledger.markUnlistedUnconfirmed(res.touchedAccountIds, req.coverage(), res.touchedEntryIds, req.provider()));
            } catch (Exception e) { res.errors.add("coverage reconciliation failed: " + e.getMessage()); }
        }
        return res;
    }

    private void process(Request req, RawRecord rec, Result res) {
        RawDataStore.Stored stored = tx.execute(s -> raw.store(req.userId(), req.connectionId(), req.syncRunId(),
            req.sourceType(), req.provider(), req.mode(), rec));
        RawFinancialData row = stored.row();
        if (!stored.created() && row.getProcessingStatus() != ProcessingStatus.RECEIVED && row.getProcessingStatus() != ProcessingStatus.FAILED) {
            res.duplicated++;
            events.emit(IngestionEvents.RECORD_DEDUPLICATED, "userId", req.userId(), "rawId", row.getId(), "reason", "payload_already_processed");
            return;
        }
        events.emit(IngestionEvents.RECORD_RECEIVED, "userId", req.userId(), "rawId", row.getId(), "source", req.sourceType(), "kind", rec.kind());
        try {
            route(req, rec, row, res);
        } catch (RuntimeException e) {
            // Left retryable: a FAILED raw record is picked up again by retryFailedSync().
            try { tx.executeWithoutResult(s -> raw.mark(row, ProcessingStatus.FAILED, e.getMessage())); }
            catch (RuntimeException ignored) { /* the original failure is the one reported */ }
            throw e;
        }
    }

    private void route(Request req, RawRecord rec, RawFinancialData row, Result res) {
        RecordNormalizer n = normalizers.stream().filter(x -> x.supports(rec.schemaVersion())).findFirst().orElse(null);
        if (n == null) {
            tx.executeWithoutResult(s -> raw.mark(row, ProcessingStatus.FAILED, "No normalizer for schema " + rec.schemaVersion()));
            res.failed++; res.rejected++;
            res.errors.add("No normalizer for schema " + rec.schemaVersion());
            return;
        }
        String payload = rec.payload() != null ? rec.payload() : raw.payload(row);
        RawRecord source = new RawRecord(rec.kind(), rec.externalAccountId(), rec.externalRecordId(), payload, rec.schemaVersion(), rec.receivedAt());

        if (rec.kind() == RecordKind.HOLDING) { processHoldings(req, n, source, row, res); return; }
        processTransactions(req, n, source, row, res);
    }

    private void processHoldings(Request req, RecordNormalizer n, RawRecord rec, RawFinancialData row, Result res) {
        List<NormalizedHolding> list;
        try { list = n.holdings(rec, req.sourceType(), req.provider()); }
        catch (IllegalArgumentException e) { reject(row, e.getMessage(), res); return; }
        if (list.isEmpty()) { tx.executeWithoutResult(s -> raw.mark(row, ProcessingStatus.PROCESSED, "no holding in record")); res.skipped++; return; }
        for (NormalizedHolding h : list) {
            List<String> errs = validator.validate(h);
            if (!errs.isEmpty()) { reject(row, String.join("; ", errs), res); return; }
            events.emit(IngestionEvents.RECORD_NORMALIZED, "userId", req.userId(), "rawId", row.getId(), "kind", "HOLDING");
            tx.executeWithoutResult(s -> {
                HoldingSnapshot snap = holdings.ingestReported(req.userId(), h, row.getId());
                raw.mark(row, ProcessingStatus.PROCESSED, null);
                res.touchedAccountIds.add(snap.getAccountId());
            });
            res.created++;
        }
    }

    private void processTransactions(Request req, RecordNormalizer n, RawRecord rec, RawFinancialData row, Result res) {
        List<NormalizedTransaction> list;
        try { list = n.transactions(rec, req.sourceType(), req.provider()); }
        catch (IllegalArgumentException e) { reject(row, e.getMessage(), res); return; }
        if (list.isEmpty()) {
            tx.executeWithoutResult(s -> raw.mark(row, ProcessingStatus.PROCESSED, "not a ledger event"));
            res.skipped++;
            return;
        }
        for (NormalizedTransaction nt : list) {
            List<String> errs = validator.validate(nt, LocalDate.now());
            if (!errs.isEmpty()) { reject(row, String.join("; ", errs), res); return; }
            events.emit(IngestionEvents.RECORD_NORMALIZED, "userId", req.userId(), "rawId", row.getId(), "kind", "TRANSACTION", "type", nt.getType());
            tx.executeWithoutResult(s -> resolveAndApply(req, row, nt, res));
        }
    }

    private void resolveAndApply(Request req, RawFinancialData row, NormalizedTransaction nt, Result res) {
        AccountResolver.Resolved acct = accounts.resolve(req.userId(), nt.getAccount());
        Long assetId = nt.getAsset() != null && nt.getAsset().identified() ? assets.resolve(nt.getAsset()).getId() : null;

        TransactionCandidate cand = candidates.findByRawRecordId(row.getId()).orElseGet(() -> candidates.save(
            TransactionCandidate.builder().userId(req.userId()).rawRecordId(row.getId()).accountId(acct.account().getId()).assetId(assetId)
                .transactionType(nt.getType()).transactionDate(nt.getTransactionDate()).settlementDate(nt.getSettlementDate())
                .quantity(nt.getQuantity()).unitPrice(nt.getUnitPrice()).grossAmount(nt.getGrossAmount()).fees(nt.getFees())
                .taxes(nt.getTaxes()).netAmount(nt.getNetAmount()).currency(nt.getCurrency())
                .sourceType(nt.getSourceType()).sourceProvider(nt.getSourceProvider()).sourceReference(nt.getSourceReference())
                .confidence(nt.getSourceType().baseConfidence() * nt.getRecordConfidence()).build()));

        TransactionMatcher.Decision d = matcher.decide(nt, assetId, acct.account().getId(), acct.specific(), ledger.pool(req.userId(), assetId, nt), req.sameSourceDistinct());
        if (d.merges()) events.emit(IngestionEvents.RECORD_DEDUPLICATED, "userId", req.userId(), "rawId", row.getId(), "matchedTransactionId", d.matchedId(), "kind", d.kind());

        LedgerService.Applied applied = ledger.apply(row, nt, acct, assetId, d, req.coverage());

        cand.setMatchKind(d.kind().name());
        cand.setDecisionExplanation(d.explanationText());
        cand.setMatchedTransactionId(applied.entry().getId());
        cand.setDecidedAt(java.time.LocalDateTime.now());
        cand.setStatus(applied.outcome() == LedgerService.Outcome.CREATED ? CandidateStatus.PROMOTED
            : d.kind() == TransactionMatcher.Kind.SAME_SOURCE_REDELIVERY ? CandidateStatus.DUPLICATE : CandidateStatus.MATCHED_EXISTING);
        // A candidate from a weak source that created its own ledger row still awaits corroboration.
        if (applied.outcome() == LedgerService.Outcome.CREATED && !nt.getSourceType().authoritative())
            cand.setStatus(CandidateStatus.PENDING_RECONCILIATION);
        candidates.save(cand);
        raw.mark(row, ProcessingStatus.PROCESSED, null);

        switch (applied.outcome()) {
            case CREATED -> res.created++;
            case UPDATED -> res.updated++;
            case MATCHED -> { res.duplicated++; res.reconciled++; }
        }
        res.touchedEntryIds.add(applied.entry().getId());
        res.touchedAccountIds.add(applied.entry().getAccountId());
        if (applied.entry().getAssetId() != null) {
            res.touchedPositions.add(List.of(applied.entry().getAccountId(), applied.entry().getAssetId()));
            // The entry moved off a placeholder account: that account's position changed too.
            if (applied.previousAccountId() != null) res.touchedPositions.add(List.of(applied.previousAccountId(), applied.entry().getAssetId()));
        }
    }

    private void reject(RawFinancialData row, String reason, Result res) {
        tx.executeWithoutResult(s -> raw.mark(row, ProcessingStatus.REJECTED, reason));
        res.rejected++;
        res.errors.add(reason);
    }
}
