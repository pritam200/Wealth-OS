package com.marketai.dataplatform.service;

import com.marketai.auth.repository.UserRepository;
import com.marketai.dataplatform.domain.ConnectionStatus;
import com.marketai.dataplatform.domain.DataConnection;
import com.marketai.dataplatform.repo.DataConnectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** Background upkeep: grace-period expiry, scheduled provider syncs, and the one-time backfill. */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataPlatformScheduler {

    private final LedgerService ledger;
    private final DataConnectionRepository connections;
    private final FinancialDataSyncService sync;
    private final LegacyBackfillService backfill;
    private final UserRepository users;

    @Value("${wealthos.data.backfill-on-startup:true}")
    private boolean backfillOnStartup;

    /** Single weak reports past their grace period become UNCONFIRMED. */
    @Scheduled(cron = "${wealthos.data.scheduler.sweep-cron:0 15 3 * * *}")
    public void sweep() {
        try {
            int n = ledger.sweepGracePeriods(LocalDateTime.now());
            log.info("event=grace_sweep_completed changed={}", n);
        } catch (Exception e) { log.warn("event=grace_sweep_failed error={}", e.getClass().getSimpleName()); }
    }

    @Scheduled(cron = "${wealthos.data.scheduler.sync-cron:0 30 5 * * *}")
    public void syncConnections() {
        for (DataConnection c : connections.findByStatus(ConnectionStatus.CONNECTED)) {
            try { sync.incrementalSync(c.getUserId(), c.getId()); }
            catch (Exception e) { log.warn("event=scheduled_sync_skipped connectionId={} error={}", c.getId(), e.getClass().getSimpleName()); }
        }
    }

    /** Existing transactions are migrated once, off the startup thread; the migration is idempotent. */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillExisting() {
        if (!backfillOnStartup) return;
        Thread t = new Thread(() -> {
            try {
                users.findAll().forEach(u -> {
                    try { backfill.backfill(u.getId()); }
                    catch (Exception e) { log.warn("event=backfill_failed userId={} error={}", u.getId(), e.getClass().getSimpleName()); }
                });
            } catch (Exception e) { log.warn("event=backfill_failed error={}", e.getClass().getSimpleName()); }
        }, "canonical-backfill");
        t.setDaemon(true);
        t.start();
    }
}
