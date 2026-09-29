package com.marketai.sync.service;

import com.marketai.auth.entity.User;
import com.marketai.sync.entity.*;
import com.marketai.sync.repository.SyncJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Lifecycle management for ingestion jobs. Deliberately knows nothing about Gmail — the
 * worker supplies the actual work, so the same queue can carry other ingestion types later.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SyncJobService {

    private final SyncJobRepository repo;

    private static final List<SyncJobStatus> ACTIVE =
        Arrays.asList(SyncJobStatus.QUEUED, SyncJobStatus.RUNNING);

    /**
     * Queues a job unless an equivalent one is already pending for this user.
     *
     * Collapsing duplicates matters most on the push path: Pub/Sub delivers at-least-once, and
     * a burst of notifications for one mailbox should produce one sync, not one per message.
     */
    @Transactional
    public SyncJob enqueue(User user, SyncJobType type, SyncTrigger trigger, String parameters) {
        if (repo.existsByUser_IdAndTypeAndStatusIn(user.getId(), type, ACTIVE)) {
            log.debug("Sync job {} already active for user {} — not queueing a duplicate", type, user.getId());
            return repo.findFirstByUser_IdOrderByCreatedAtDesc(user.getId()).orElse(null);
        }
        SyncJob job = repo.save(SyncJob.builder()
            .user(user).type(type).trigger(trigger).parameters(parameters)
            .status(SyncJobStatus.QUEUED)
            .build());
        log.info("Queued sync job {} ({} / {}) for user {}", job.getId(), type, trigger, user.getId());
        return job;
    }

    /** @return true when this worker won the race for the job. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(Long jobId, String workerId) {
        return repo.claim(jobId, workerId, LocalDateTime.now()) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(Long jobId, String resultSummary, Integer itemsProcessed) {
        complete(jobId, SyncJobStatus.SUCCEEDED, resultSummary, itemsProcessed);
    }

    /** Finishes a job that ran to the end, with the outcome it actually had. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(Long jobId, SyncJobStatus outcome, String resultSummary, Integer itemsProcessed) {
        repo.findById(jobId).ifPresent(job -> {
            job.setStatus(outcome);
            job.setResultSummary(truncate(resultSummary, 20_000));
            if (itemsProcessed != null) job.setItemsProcessed(itemsProcessed);
            job.setFinishedAt(LocalDateTime.now());
            job.setLastError(null);
            repo.save(job);
        });
    }

    /**
     * Records a failure. Requeues while retries remain, because most failures here are
     * transient (token refresh, Gmail 5xx, a slow model call) and a permanent FAILED would
     * silently drop that mailbox window.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long jobId, String error) {
        repo.findById(jobId).ifPresent(job -> {
            job.setLastError(truncate(error, 1000));
            boolean retriable = job.getAttempts() < job.getMaxAttempts();
            job.setStatus(retriable ? SyncJobStatus.QUEUED : SyncJobStatus.FAILED);
            job.setClaimedBy(null);
            job.setClaimedAt(null);
            if (!retriable) {
                job.setFinishedAt(LocalDateTime.now());
                log.error("Sync job {} failed permanently after {} attempts: {}",
                    jobId, job.getAttempts(), error);
            } else {
                log.warn("Sync job {} failed (attempt {}/{}), requeued: {}",
                    jobId, job.getAttempts(), job.getMaxAttempts(), error);
            }
            repo.save(job);
        });
    }

    /**
     * Puts a claimed job back in the queue without using up an attempt — for a job that could
     * not start because another sync for the same user was already running.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(Long jobId) {
        repo.findById(jobId).ifPresent(job -> {
            job.setStatus(SyncJobStatus.QUEUED);
            job.setAttempts(Math.max(0, job.getAttempts() - 1));
            job.setClaimedBy(null);
            job.setClaimedAt(null);
            repo.save(job);
        });
    }

    /** Whether a job for this user is running, so the worker doesn't start a second one. */
    public boolean hasRunningJob(Long userId) {
        return repo.existsByUser_IdAndStatusIn(userId, List.of(SyncJobStatus.RUNNING));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void progress(Long jobId, int processed, Integer total) {
        repo.findById(jobId).ifPresent(job -> {
            job.setItemsProcessed(processed);
            if (total != null) job.setItemsTotal(total);
            repo.save(job);
        });
    }

    public List<SyncJob> nextQueued(int limit) {
        return repo.findByStatusOrderByCreatedAtAsc(
            SyncJobStatus.QUEUED, org.springframework.data.domain.PageRequest.of(0, limit));
    }

    public Optional<SyncJob> get(Long jobId, Long userId) {
        return repo.findByIdAndUser_Id(jobId, userId);
    }

    public List<SyncJob> recentFor(Long userId, int limit) {
        return repo.findByUser_IdOrderByCreatedAtDesc(
            userId, org.springframework.data.domain.PageRequest.of(0, limit));
    }

    /**
     * Returns jobs stuck in RUNNING to the queue. A job whose worker died mid-run would
     * otherwise stay RUNNING forever, and the duplicate guard would refuse every later sync
     * for that user. A job whose user's sync lock is still being refreshed is alive — however
     * long it has run — and is left alone; requeuing it would run the same sync twice.
     */
    @Transactional
    public int requeueStale(java.time.Duration staleAfter, java.util.function.LongPredicate syncStillRunning) {
        int requeued = 0;
        for (SyncJob job : repo.findStaleRunning(LocalDateTime.now().minus(staleAfter))) {
            if (job.getUser() != null && syncStillRunning.test(job.getUser().getId())) continue;
            log.warn("Requeuing stale job {} (claimed by {} at {})",
                job.getId(), job.getClaimedBy(), job.getClaimedAt());
            job.setStatus(job.getAttempts() < job.getMaxAttempts()
                ? SyncJobStatus.QUEUED : SyncJobStatus.FAILED);
            job.setClaimedBy(null);
            job.setClaimedAt(null);
            if (job.getStatus() == SyncJobStatus.FAILED) {
                job.setLastError("Worker did not finish; exceeded retry budget.");
                job.setFinishedAt(LocalDateTime.now());
            }
            repo.save(job);
            requeued++;
        }
        return requeued;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "…[truncated]" : s;
    }
}
