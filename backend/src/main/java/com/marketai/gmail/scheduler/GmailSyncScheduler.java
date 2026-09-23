package com.marketai.gmail.scheduler;

import com.marketai.gmail.entity.GmailToken;
import com.marketai.gmail.repository.GmailTokenRepository;
import com.marketai.gmail.service.GmailWatchService;
import com.marketai.sync.entity.SyncJobType;
import com.marketai.sync.entity.SyncTrigger;
import com.marketai.sync.service.SyncJobService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Timers for mail ingestion.
 *
 * This no longer runs syncs itself. It enqueues jobs, so the work happens on the worker pool
 * with retries, progress and a durable record — previously a scheduled sync that failed
 * halfway left nothing behind to show it had ever run.
 */
@Component
@RequiredArgsConstructor
public class GmailSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(GmailSyncScheduler.class);

    private final GmailTokenRepository tokenRepo;
    private final SyncJobService jobService;
    private final GmailWatchService watchService;

    /**
     * Safety-net poll. Gmail caps push notifications at one per second per user and silently
     * drops the excess, so notifications are lossy by design and a periodic sweep is required
     * even when push is healthy.
     *
     * Enqueues an incremental job; the runner falls back to a full scan by itself when no
     * usable watermark exists.
     */
    @Scheduled(fixedDelay = 1_800_000)
    public void scheduledSync() {
        for (GmailToken token : tokenRepo.findAll()) {
            try {
                if (token.getUser() == null) continue;
                jobService.enqueue(token.getUser(), SyncJobType.GMAIL_INCREMENTAL_SYNC,
                    SyncTrigger.SCHEDULED, null);
            } catch (Exception e) {
                log.error("Could not queue scheduled sync for token {}: {}", token.getId(), e.getMessage());
            }
        }
    }

    /**
     * Backlog sweep. A message that failed, hit a parser gap, or is still sitting in review
     * only ever gets another chance when re-scanned — and {@link #scheduledSync} above only
     * ever looks forward from the last watermark, so a message that first failed weeks ago
     * would otherwise sit un-retried forever even after a parser fix ships. Runs far less often
     * than the incremental poll: these ids don't go stale by the hour, and re-fetching every
     * backlog message from Gmail on every run would burn quota for no benefit between parser
     * changes.
     */
    @Scheduled(fixedDelay = 604_800_000, initialDelay = 300_000)
    public void scheduledRetrySweep() {
        for (GmailToken token : tokenRepo.findAll()) {
            try {
                if (token.getUser() == null) continue;
                jobService.enqueue(token.getUser(), SyncJobType.GMAIL_RETRY_FAILED,
                    SyncTrigger.SCHEDULED, null);
            } catch (Exception e) {
                log.error("Could not queue backlog retry sweep for token {}: {}", token.getId(), e.getMessage());
            }
        }
    }

    /**
     * Renews Gmail push registrations.
     *
     * A watch expires after 7 days and then simply stops delivering, with no error and no
     * callback — the single most dangerous silent-failure mode in this pipeline. Google
     * recommends renewing daily, which is what this does; renewing well before expiry means a
     * few consecutive failures still leave days of margin.
     */
    @Scheduled(fixedDelay = 86_400_000, initialDelay = 120_000)
    public void renewWatches() {
        try {
            watchService.renewAllDueWatches();
        } catch (Exception e) {
            log.error("Gmail watch renewal sweep failed: {}", e.getMessage());
        }
    }
}
