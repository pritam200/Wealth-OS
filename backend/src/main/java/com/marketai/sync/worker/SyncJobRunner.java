package com.marketai.sync.worker;

import com.google.api.services.gmail.Gmail;
import com.marketai.gmail.dto.GmailSyncResult;
import com.marketai.gmail.entity.GmailToken;
import com.marketai.gmail.repository.GmailTokenRepository;
import com.marketai.gmail.service.GmailClientService;
import com.marketai.gmail.service.GmailIncrementalSyncService;
import com.marketai.gmail.service.GmailSyncService;
import com.marketai.sync.entity.SyncJob;
import com.marketai.sync.entity.SyncJobType;
import com.marketai.sync.repository.SyncJobRepository;
import com.marketai.sync.service.SyncJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Executes one sync job.
 *
 * Holds the watermark discipline for incremental sync, because this is the only place that
 * knows whether the delta was fully drained. The rules are not defensive extras — each one
 * corresponds to a documented way of permanently losing a user's transactions.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SyncJobRunner {

    private final SyncJobRepository jobRepo;
    private final SyncJobService jobService;
    private final GmailTokenRepository tokenRepo;
    private final GmailClientService gmailClient;
    private final GmailSyncService gmailSyncService;
    private final GmailIncrementalSyncService incrementalService;

    public void run(Long jobId) {
        SyncJob job = jobRepo.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("Sync job {} vanished before it could run", jobId);
            return;
        }
        Long userId = job.getUser().getId();
        log.info("Running sync job {} ({}) for user {}", jobId, job.getType(), userId);

        try {
            GmailSyncResult result;
            switch (job.getType()) {
                case GMAIL_INCREMENTAL_SYNC:
                    result = runIncremental(jobId, userId);
                    break;
                case GMAIL_FULL_SYNC:
                case GMAIL_RETRY_FAILED:
                default:
                    result = runFull(userId, job.getParameters());
                    break;
            }

            if (result != null && result.getError() != null) {
                jobService.fail(jobId, result.getError());
                return;
            }
            int imported = result != null ? result.getImported() : 0;
            jobService.succeed(jobId, summarize(result), imported);

        } catch (Exception e) {
            log.error("Sync job {} threw: {}", jobId, e.getMessage(), e);
            jobService.fail(jobId, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Full window scan. Captures a fresh baseline watermark so later runs can go incremental. */
    private GmailSyncResult runFull(Long userId, String lookback) {
        String window = (lookback == null || lookback.trim().isEmpty()) ? "14d" : lookback.trim();

        // Read the mailbox's current historyId BEFORE scanning. Anything that arrives while the
        // scan runs then falls after this mark and is picked up next time, rather than landing
        // in the gap between "scan finished" and "watermark written".
        String baseline = currentHistoryId(userId);

        GmailSyncResult result = gmailSyncService.syncForUser(userId, window);

        if (result != null && result.getError() == null && baseline != null) {
            persistWatermark(userId, baseline, "baseline captured before full sync");
        }
        return result;
    }

    /**
     * Delta sync. Falls back to a full scan whenever the delta cannot be trusted, and only
     * advances the watermark when every page was drained.
     */
    private GmailSyncResult runIncremental(Long jobId, Long userId) {
        GmailToken token = tokenRepo.findByUserId(userId).orElse(null);
        if (token == null) {
            return errorResult("Gmail not connected.");
        }

        Gmail gmail;
        try {
            gmail = buildGmail(token, userId);
        } catch (Exception e) {
            return errorResult("Could not open Gmail: " + e.getMessage());
        }

        GmailIncrementalSyncService.Delta delta =
            incrementalService.fetchDelta(gmail, token.getLastHistoryId());

        if (!delta.isUsable()) {
            // Documented recovery for an aged-out historyId is a full sync. Anything else
            // would skip every message between the stale mark and now.
            log.info("Incremental sync unusable for user {} ({}) — running full sync", userId, delta.getReason());
            return runFull(userId, "14d");
        }

        List<String> ids = delta.getAddedMessageIds();
        jobService.progress(jobId, 0, ids.size());

        GmailSyncResult result = ids.isEmpty() ? null : gmailSyncService.syncSpecificMessages(userId, ids);

        // The watermark moves ONLY on a fully drained delta. A partial read that advanced the
        // mark would permanently skip whatever was left undrained.
        if (delta.isComplete() && (result == null || result.getError() == null)) {
            persistWatermark(userId, delta.getNewHistoryId(), delta.getReason());
        } else if (!delta.isComplete()) {
            log.warn("Delta incomplete for user {} — watermark left at {} so the remainder is retried: {}",
                userId, token.getLastHistoryId(), delta.getReason());
        }

        if (result == null) {
            // Nothing new. Still a successful run — Gmail's history index often lags a
            // notification, and an empty delta is the normal steady state.
            return GmailSyncResult.builder()
                .imported(0).skipped(0).failed(0)
                .summaries(new java.util.ArrayList<>())
                .logEntries(new java.util.ArrayList<>())
                .error(null)
                .build();
        }
        return result;
    }

    private Gmail buildGmail(GmailToken token, Long userId) throws Exception {
        return gmailClient.buildGmailService(token.getAccessToken(), token.getRefreshToken(),
            (newAccess, newRefresh, expiresIn) -> {
                token.setAccessToken(newAccess);
                if (newRefresh != null) token.setRefreshToken(newRefresh);
                token.setExpiresAt(LocalDateTime.now().plusSeconds(expiresIn != null ? expiresIn : 3600));
                tokenRepo.save(token);
            });
    }

    /** Reads the mailbox's current historyId from the profile, or null if unavailable. */
    private String currentHistoryId(Long userId) {
        try {
            GmailToken token = tokenRepo.findByUserId(userId).orElse(null);
            if (token == null) return null;
            Gmail gmail = buildGmail(token, userId);
            java.math.BigInteger id = gmail.users().getProfile("me").execute().getHistoryId();
            return id != null ? id.toString() : null;
        } catch (Exception e) {
            // Not fatal: without a baseline the next run simply stays on the full path.
            log.warn("Could not read profile historyId for user {}: {}", userId, e.getMessage());
            return null;
        }
    }

    /** Applies the monotonic-advance rule and persists. */
    private void persistWatermark(Long userId, String candidate, String why) {
        if (candidate == null) return;
        tokenRepo.findByUserId(userId).ifPresent(t -> {
            String next = incrementalService.advanceWatermark(t.getLastHistoryId(), candidate);
            if (next == null) {
                log.debug("Watermark for user {} not advanced (stored={}, candidate={})",
                    userId, t.getLastHistoryId(), candidate);
                return;
            }
            t.setLastHistoryId(next);
            t.setHistoryIdUpdatedAt(LocalDateTime.now());
            tokenRepo.save(t);
            log.info("Watermark for user {} advanced to {} ({})", userId, next, why);
        });
    }

    private GmailSyncResult errorResult(String error) {
        return GmailSyncResult.builder()
            .imported(0).skipped(0).failed(0)
            .summaries(new java.util.ArrayList<>())
            .logEntries(new java.util.ArrayList<>())
            .error(error)
            .build();
    }

    private String summarize(GmailSyncResult r) {
        if (r == null) return "No new messages.";
        return String.format("imported=%d skipped=%d failed=%d", r.getImported(), r.getSkipped(), r.getFailed());
    }
}
