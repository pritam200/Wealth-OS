package com.marketai.gmail.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketai.gmail.entity.GmailToken;
import com.marketai.gmail.repository.GmailTokenRepository;
import com.marketai.sync.entity.SyncJobType;
import com.marketai.sync.entity.SyncTrigger;
import com.marketai.sync.service.SyncJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Base64;
import java.util.Map;

/**
 * Receives Gmail push notifications delivered via Google Cloud Pub/Sub.
 *
 * Design rules, all forced by how Pub/Sub push actually behaves:
 *
 *  - **Always ack.** Push subscriptions can't extend a per-message deadline, and a non-200
 *    triggers redelivery with backoff. Since the real work takes minutes, this endpoint only
 *    enqueues and returns 200 immediately. Returning an error to signal "processing failed"
 *    would just produce a redelivery storm.
 *  - **Delivery is at-least-once and unordered.** Duplicates are expected, so the handler is
 *    idempotent: it enqueues at most one active job per user, and the historyId watermark is
 *    monotonic regardless of the order notifications arrive in.
 *  - **The notification's historyId is deliberately ignored** for watermark purposes. It is a
 *    mailbox watermark that can sit ahead of what we actually processed; the runner reads the
 *    real one from the history.list response instead.
 */
@RestController
@RequestMapping("/api/gmail/push")
@RequiredArgsConstructor
@Slf4j
public class GmailPushController {

    private final GmailTokenRepository tokenRepo;
    private final SyncJobService jobService;
    private final ObjectMapper objectMapper;

    /**
     * Shared secret in the push endpoint URL (?token=...). This endpoint is unauthenticated by
     * necessity — Google calls it — so without a secret anyone could trigger syncs.
     */
    @Value("${app.gmail.push.verification-token:}")
    private String verificationToken;

    /** Push off (the default) means nothing legitimate calls this endpoint at all. */
    @Value("${app.gmail.push.enabled:false}")
    private boolean pushEnabled;

    @PostMapping
    public ResponseEntity<Void> receive(@RequestBody Map<String, Object> body,
                                        @RequestParam(required = false) String token) {
        // Fail closed. Previously a blank secret — the shipped default — short-circuited the
        // comparison and accepted every caller on an endpoint that is permitAll and kicks off
        // minutes of LLM work per request. An unconfigured or disabled push path must accept
        // nothing rather than everything.
        if (!pushEnabled) {
            log.debug("Gmail push received while push is disabled — ignoring");
            return ResponseEntity.ok().build();
        }
        if (verificationToken == null || verificationToken.trim().isEmpty()) {
            log.warn("Gmail push rejected: app.gmail.push.verification-token is not configured");
            return ResponseEntity.ok().build();
        }
        if (!verificationToken.equals(token)) {
            log.warn("Rejected Gmail push with a bad verification token");
            // 200 anyway: a 4xx makes Pub/Sub retry a request that will never succeed.
            return ResponseEntity.ok().build();
        }

        try {
            Object messageObj = body.get("message");
            if (messageObj instanceof Map) {
                Object data = ((Map<?, ?>) messageObj).get("data");
                if (data != null) {
                    String json = new String(Base64.getDecoder().decode(data.toString()), "UTF-8");
                    JsonNode node = objectMapper.readTree(json);
                    String emailAddress = node.path("emailAddress").asText(null);
                    if (emailAddress != null) {
                        enqueueFor(emailAddress);
                    }
                }
            }
        } catch (Exception e) {
            // Swallowed deliberately — see the "always ack" rule above. The scheduled poll is
            // the backstop for anything a malformed notification caused us to miss.
            log.warn("Could not handle Gmail push notification: {}", e.getMessage());
        }
        return ResponseEntity.ok().build();
    }

    private void enqueueFor(String emailAddress) {
        GmailToken token = tokenRepo.findByConnectedEmailIgnoreCase(emailAddress).orElse(null);
        if (token == null || token.getUser() == null) {
            log.debug("Gmail push for unknown mailbox {} — ignoring", emailAddress);
            return;
        }
        // enqueue() collapses duplicates, so a burst of notifications for one mailbox
        // produces a single sync rather than one per message.
        jobService.enqueue(token.getUser(), SyncJobType.GMAIL_INCREMENTAL_SYNC, SyncTrigger.PUSH, null);
    }
}
