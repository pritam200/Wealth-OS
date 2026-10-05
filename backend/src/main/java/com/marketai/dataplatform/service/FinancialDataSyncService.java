package com.marketai.dataplatform.service;

import com.marketai.dataplatform.domain.*;
import com.marketai.dataplatform.pipeline.RawRecord;
import com.marketai.dataplatform.provider.FinancialDataProvider;
import com.marketai.dataplatform.provider.ProviderRegistry;
import com.marketai.dataplatform.repo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Synchronisation of a connection with its provider, whatever the provider is.
 *
 * <ul>
 *   <li>{@link #initialSync} — the first pull, over the consented history.</li>
 *   <li>{@link #incrementalSync} — from the saved cursor.</li>
 *   <li>{@link #fullReconciliation} — re-pull the whole window and reconcile the ledger against it.</li>
 *   <li>{@link #retryFailedSync} — reprocess raw records that failed.</li>
 * </ul>
 * Every run records its counts and errors, and updates the connection's last-sync fields that the
 * UI shows. A run that fails leaves already-ingested records in place; a re-run is idempotent.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FinancialDataSyncService {

    private final ProviderRegistry providers;
    private final DataConnectionRepository connections;
    private final ConsentRecordRepository consents;
    private final FinancialSyncRunRepository runs;
    private final RawFinancialDataRepository rawRepo;
    private final RawDataStore rawStore;
    private final IngestionPipeline pipeline;
    private final IngestionEvents events;

    public FinancialSyncRun initialSync(Long userId, Long connectionId) { return run(userId, connectionId, SyncKind.INITIAL); }

    public FinancialSyncRun incrementalSync(Long userId, Long connectionId) { return run(userId, connectionId, SyncKind.INCREMENTAL); }

    public FinancialSyncRun fullReconciliation(Long userId, Long connectionId) { return run(userId, connectionId, SyncKind.FULL_RECONCILIATION); }

    public FinancialSyncRun retryFailedSync(Long userId, Long connectionId) { return run(userId, connectionId, SyncKind.RETRY_FAILED); }

    public List<FinancialSyncRun> recent(Long userId) { return runs.findTop20ByUserIdOrderByStartedAtDesc(userId); }

    FinancialSyncRun run(Long userId, Long connectionId, SyncKind kind) {
        DataConnection conn = connections.findByIdAndUserId(connectionId, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found"));
        if (conn.getStatus() != ConnectionStatus.CONNECTED)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Connection is not active (" + conn.getStatus() + ")");
        if (conn.getSyncStatus() == SyncStatus.RUNNING)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A sync is already running for this connection");
        FinancialDataProvider provider = providers.find(conn.getProviderId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Provider " + conn.getProviderId() + " is not configured"));

        LocalDateTime started = LocalDateTime.now();
        FinancialSyncRun run = runs.save(FinancialSyncRun.builder().userId(userId).connectionId(connectionId).kind(kind).startedAt(started).build());
        conn.setSyncStatus(SyncStatus.RUNNING); conn.setLastSyncAt(started);
        connections.save(conn);
        events.emit(IngestionEvents.SYNC_STARTED, "userId", userId, "connectionId", connectionId, "kind", kind, "provider", provider.providerId());

        List<String> errors = new ArrayList<>();
        try {
            List<RawRecord> records;
            LedgerService.Coverage coverage = null;
            String nextCursor = conn.getSyncCursor();
            if (kind == SyncKind.RETRY_FAILED) {
                records = new ArrayList<>();
                for (RawFinancialData r : rawRepo.findByConnectionIdAndProcessingStatus(connectionId, ProcessingStatus.FAILED))
                    records.add(new RawRecord(r.getRecordKind(), r.getExternalAccountId(), r.getExternalRecordId(), rawStore.payload(r),
                        r.getSchemaVersion(), r.getReceivedAt()));
            } else {
                String consentHandle = conn.getConsentId() == null ? null
                    : consents.findById(conn.getConsentId()).map(ConsentRecord::getProviderConsentHandle).orElse(null);
                String cursor = kind == SyncKind.INCREMENTAL ? conn.getSyncCursor() : null;
                LocalDate to = LocalDate.now();
                LocalDate from = kind == SyncKind.INCREMENTAL && conn.getLastSuccessfulSyncAt() != null
                    ? conn.getLastSuccessfulSyncAt().toLocalDate().minusDays(1) : to.minusYears(1);
                FinancialDataProvider.FetchResult fr = provider.fetch(new FinancialDataProvider.FetchRequest(userId, connectionId, consentHandle, cursor, from, to, kind));
                records = fr.records();
                nextCursor = fr.nextCursor() != null ? fr.nextCursor() : nextCursor;
                if (fr.coverageFrom() != null && fr.coverageTo() != null) coverage = new LedgerService.Coverage(fr.coverageFrom(), fr.coverageTo());
            }
            IngestionPipeline.Result res = pipeline.ingest(new IngestionPipeline.Request(userId, connectionId, run.getId(),
                provider.sourceType(), provider.providerId(), provider.mode(), records, coverage));

            run.setRecordsFetched(res.fetched); run.setRecordsCreated(res.created); run.setRecordsUpdated(res.updated);
            run.setRecordsDuplicated(res.duplicated); run.setRecordsRejected(res.rejected); run.setRecordsReconciled(res.reconciled);
            errors.addAll(res.errors);
            boolean clean = res.errors.isEmpty();
            run.setStatus(clean ? SyncStatus.SUCCEEDED : SyncStatus.PARTIAL);
            conn.setSyncStatus(run.getStatus());
            conn.setLastSuccessfulSyncAt(LocalDateTime.now());
            conn.setSyncCursor(nextCursor);
            conn.setLastError(clean ? null : cut(res.errors.get(0)));
            events.emit(IngestionEvents.SYNC_COMPLETED, "userId", userId, "connectionId", connectionId, "kind", kind,
                "fetched", res.fetched, "created", res.created, "duplicated", res.duplicated, "rejected", res.rejected);
        } catch (Exception e) {
            // The message may come from a provider; log the class and a truncated message only.
            String msg = e.getClass().getSimpleName() + ": " + cut(String.valueOf(e.getMessage()));
            errors.add(msg);
            run.setStatus(SyncStatus.FAILED);
            conn.setSyncStatus(SyncStatus.FAILED);
            conn.setLastError(msg);
            events.emit(IngestionEvents.SYNC_FAILED, "userId", userId, "connectionId", connectionId, "kind", kind, "error", msg);
        }
        run.setFinishedAt(LocalDateTime.now());
        run.setErrors(errors.isEmpty() ? null : String.join("\n", errors.stream().limit(50).toList()));
        connections.save(conn);
        return runs.save(run);
    }

    private static String cut(String s) { return s == null || s.length() <= 500 ? s : s.substring(0, 500); }
}
