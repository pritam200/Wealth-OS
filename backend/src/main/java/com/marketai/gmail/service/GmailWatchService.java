package com.marketai.gmail.service;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.WatchRequest;
import com.google.api.services.gmail.model.WatchResponse;
import com.marketai.gmail.entity.GmailToken;
import com.marketai.gmail.repository.GmailTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;

/**
 * Manages Gmail push registrations (`users.watch`).
 *
 * The failure mode this exists to prevent: a watch lasts 7 days and then stops delivering
 * notifications with no error, no callback and no observable symptom other than mail quietly
 * no longer being imported. Renewal is therefore proactive and on a timer, and a failure to
 * renew is logged loudly rather than swallowed.
 *
 * Push is treated strictly as a latency optimisation. The scheduled poll is never disabled,
 * because Gmail caps notifications at one per second per user and drops the excess.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GmailWatchService {

    private final GmailTokenRepository tokenRepo;
    private final GmailClientService gmailClient;

    /** Pub/Sub topic that Gmail publishes to, e.g. projects/my-proj/topics/gmail-push. */
    @Value("${app.gmail.push.topic:}")
    private String topicName;

    @Value("${app.gmail.push.enabled:false}")
    private boolean pushEnabled;

    /** Renew when fewer than this many days remain, so transient failures still have margin. */
    private static final long RENEW_WHEN_DAYS_LEFT = 3;

    public boolean isConfigured() {
        return pushEnabled && topicName != null && !topicName.trim().isEmpty();
    }

    /**
     * Registers or renews the watch for one user.
     *
     * Calling watch again renews the existing registration rather than stacking a second one,
     * so this is safe to run on a timer.
     *
     * @return the new expiration, or null when push is not configured or registration failed.
     */
    public LocalDateTime registerWatch(Long userId) {
        if (!isConfigured()) {
            log.debug("Gmail push not configured — skipping watch registration for user {}", userId);
            return null;
        }
        GmailToken token = tokenRepo.findByUserId(userId).orElse(null);
        if (token == null) return null;

        try {
            Gmail gmail = gmailClient.buildGmailService(token.getAccessToken(), token.getRefreshToken(),
                (newAccess, newRefresh, expiresIn) -> {
                    token.setAccessToken(newAccess);
                    if (newRefresh != null) token.setRefreshToken(newRefresh);
                    token.setExpiresAt(LocalDateTime.now().plusSeconds(expiresIn != null ? expiresIn : 3600));
                    tokenRepo.save(token);
                });

            WatchRequest request = new WatchRequest()
                .setTopicName(topicName)
                .setLabelIds(Collections.singletonList("INBOX"))
                .setLabelFilterBehavior("INCLUDE");

            WatchResponse response = gmail.users().watch("me", request).execute();

            LocalDateTime expiry = response.getExpiration() == null ? null
                : Instant.ofEpochMilli(response.getExpiration()).atZone(ZoneId.systemDefault()).toLocalDateTime();

            token.setWatchExpiration(expiry);

            // watch returns the mailbox historyId at registration time. Seed the watermark
            // with it only if we have none — overwriting an existing, lower mark would skip
            // everything between it and now.
            if (response.getHistoryId() != null
                    && (token.getLastHistoryId() == null || token.getLastHistoryId().trim().isEmpty())) {
                token.setLastHistoryId(response.getHistoryId().toString());
                token.setHistoryIdUpdatedAt(LocalDateTime.now());
                log.info("Seeded history watermark for user {} from watch: {}", userId, response.getHistoryId());
            }
            tokenRepo.save(token);

            log.info("Gmail watch registered for user {} — expires {}", userId, expiry);
            return expiry;

        } catch (Exception e) {
            // Loud on purpose: a silently un-renewed watch stops all push ingestion.
            log.error("Gmail watch registration FAILED for user {} — push notifications will lapse: {}",
                userId, e.getMessage());
            return null;
        }
    }

    /** Renews every registration that is missing or close to expiry. */
    public int renewAllDueWatches() {
        if (!isConfigured()) return 0;
        int renewed = 0;
        LocalDateTime threshold = LocalDateTime.now().plusDays(RENEW_WHEN_DAYS_LEFT);

        for (GmailToken token : tokenRepo.findAll()) {
            if (token.getUser() == null) continue;
            LocalDateTime expiry = token.getWatchExpiration();
            boolean due = expiry == null || expiry.isBefore(threshold);
            if (!due) continue;
            if (registerWatch(token.getUser().getId()) != null) renewed++;
        }
        if (renewed > 0) log.info("Renewed {} Gmail watch registration(s)", renewed);
        return renewed;
    }

    /** True when push is configured and the registration has not lapsed. */
    public boolean isWatchActive(GmailToken token) {
        return isConfigured()
            && token != null
            && token.getWatchExpiration() != null
            && token.getWatchExpiration().isAfter(LocalDateTime.now());
    }

    public void stopWatch(Long userId) {
        GmailToken token = tokenRepo.findByUserId(userId).orElse(null);
        if (token == null) return;
        try {
            Gmail gmail = gmailClient.buildGmailService(token.getAccessToken(), token.getRefreshToken());
            gmail.users().stop("me").execute();
        } catch (Exception e) {
            log.warn("Could not stop Gmail watch for user {}: {}", userId, e.getMessage());
        }
        token.setWatchExpiration(null);
        tokenRepo.save(token);
    }
}
