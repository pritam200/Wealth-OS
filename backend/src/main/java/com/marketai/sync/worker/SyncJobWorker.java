package com.marketai.sync.worker;

import com.marketai.sync.entity.SyncJob;
import com.marketai.sync.service.SyncJobService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Drains the sync-job queue on a bounded pool, off the request thread.
 *
 * Concurrency is deliberately small. The expensive step is the local LLM classifier (~8s per
 * unparsed email, measured), and running several of those at once on one machine makes every
 * one of them slower rather than finishing sooner. One in-flight job per user is also what
 * keeps Gmail's per-user quota (6,000 units/min) from being blown by parallel syncs.
 */
@Component
@Slf4j
public class SyncJobWorker {

    private final SyncJobService jobService;
    private final SyncJobRunner runner;

    @Value("${app.sync.worker.concurrency:2}")
    private int concurrency;

    /** A job claimed but not finished within this window is assumed dead and requeued. */
    @Value("${app.sync.worker.stale-after-minutes:90}")
    private int staleAfterMinutes;

    private ExecutorService pool;
    private final String workerId = "worker-" + java.util.UUID.randomUUID().toString().substring(0, 8);

    /** Jobs this JVM is currently running, so the poller doesn't re-dispatch them. */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public SyncJobWorker(SyncJobService jobService, SyncJobRunner runner) {
        this.jobService = jobService;
        this.runner = runner;
    }

    @PostConstruct
    void start() {
        pool = new ThreadPoolExecutor(
            concurrency, concurrency, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            r -> {
                Thread t = new Thread(r, "sync-worker");
                t.setDaemon(true);   // never block JVM shutdown on ingestion work
                return t;
            });
        // Anything left RUNNING belongs to a previous process that died — reclaim it now so a
        // crash mid-sync doesn't wedge that user's queue permanently.
        int requeued = jobService.requeueStale(Duration.ZERO);
        if (requeued > 0) log.info("Requeued {} job(s) orphaned by a previous run", requeued);
        log.info("Sync worker {} started with concurrency {}", workerId, concurrency);
    }

    @PreDestroy
    void stop() {
        if (pool != null) pool.shutdownNow();
    }

    /** Poll for work. Push/manual triggers enqueue instantly; this just picks jobs up. */
    @Scheduled(fixedDelay = 5_000, initialDelay = 10_000)
    public void poll() {
        try {
            int capacity = concurrency - inFlight.size();
            if (capacity <= 0) return;

            List<SyncJob> queued = jobService.nextQueued(capacity);
            for (SyncJob job : queued) {
                if (!inFlight.add(job.getId())) continue;          // already running here
                if (!jobService.claim(job.getId(), workerId)) {     // lost the race
                    inFlight.remove(job.getId());
                    continue;
                }
                pool.submit(() -> {
                    try {
                        runner.run(job.getId());
                    } catch (Throwable t) {
                        // Caught here as well as in the runner: an error escaping this lambda
                        // would kill the pool thread silently and shrink capacity for good.
                        log.error("Unhandled error in sync job {}", job.getId(), t);
                        jobService.fail(job.getId(), t.getMessage());
                    } finally {
                        inFlight.remove(job.getId());
                    }
                });
            }
        } catch (Exception e) {
            log.error("Sync worker poll failed: {}", e.getMessage());
        }
    }

    /** Safety net for jobs whose worker died without releasing them. */
    @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    public void reclaimStale() {
        try {
            jobService.requeueStale(Duration.ofMinutes(staleAfterMinutes));
        } catch (Exception e) {
            log.warn("Stale-job reclaim failed: {}", e.getMessage());
        }
    }
}
