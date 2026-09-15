package com.marketai.gmail.service;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.History;
import com.google.api.services.gmail.model.HistoryMessageAdded;
import com.google.api.services.gmail.model.ListHistoryResponse;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Delta sync via Gmail's history API.
 *
 * Replaces re-scanning a 14-day window on every run. Beyond being slow, the old approach was
 * quota-hostile: messages.get costs 20 units each against a 6,000 unit/user/minute ceiling, so
 * a 300-message scan consumed roughly the entire per-minute budget. history.list costs 2.
 *
 * The correctness rules encoded here come from Gmail's documented sync guidance, and each one
 * exists because violating it loses a real transaction:
 *
 *  - A 404 from history.list means the stored historyId has aged out of Gmail's retention
 *    (documented as "typically at least a week", sometimes hours). The ONLY valid recovery is
 *    a full sync. Treating 404 as an empty delta would skip everything in between.
 *  - Every page must be drained before the watermark advances. Advancing after a partial read
 *    permanently skips the undrained remainder.
 *  - The watermark comes from the history.list response, never from the push notification.
 *    The notification's id is a mailbox watermark that may sit ahead of what we processed.
 *  - An empty result does not advance anything. Gmail's history index lags notifications, so
 *    "no records yet" is routinely a timing artefact rather than "nothing happened".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GmailIncrementalSyncService {

    /** Gmail returns 404 once a startHistoryId is older than its retention window. */
    private static final int HISTORY_TOO_OLD = 404;

    private static final long MAX_PAGES = 50;      // guards against an unbounded page loop

    @Getter
    public static class Delta {
        /** Distinct message ids added since the watermark, in first-seen order. */
        private final List<String> addedMessageIds;
        /** The historyId to persist — only meaningful when {@link #complete} is true. */
        private final String newHistoryId;
        /** False when the caller must fall back to a full sync. */
        private final boolean usable;
        /** True only when every page was drained without error. */
        private final boolean complete;
        private final String reason;

        Delta(List<String> ids, String newHistoryId, boolean usable, boolean complete, String reason) {
            this.addedMessageIds = ids;
            this.newHistoryId = newHistoryId;
            this.usable = usable;
            this.complete = complete;
            this.reason = reason;
        }

        static Delta fullSyncRequired(String reason) {
            return new Delta(new ArrayList<>(), null, false, false, reason);
        }
    }

    /**
     * Fetches message ids added since {@code startHistoryId}.
     *
     * @return a {@link Delta}. When {@code usable} is false the caller MUST run a full sync;
     *         when {@code complete} is false the caller MUST NOT persist the watermark.
     */
    public Delta fetchDelta(Gmail gmail, String startHistoryId) {
        if (startHistoryId == null || startHistoryId.trim().isEmpty()) {
            return Delta.fullSyncRequired("No history watermark stored yet — first sync must be full.");
        }

        Set<String> added = new LinkedHashSet<>();   // de-duplicates ids across pages
        String pageToken = null;
        String latestHistoryId = null;
        int pages = 0;

        do {
            ListHistoryResponse resp;
            try {
                Gmail.Users.History.List req = gmail.users().history().list("me")
                    .setStartHistoryId(new BigInteger(startHistoryId.trim()))
                    // Only additions matter for ingestion. Note that Gmail still advances
                    // historyId for excluded types, so gaps in the returned records are
                    // expected and are not evidence of loss.
                    .setHistoryTypes(java.util.Collections.singletonList("messageAdded"))
                    .setMaxResults(500L);
                if (pageToken != null) req.setPageToken(pageToken);
                resp = req.execute();

            } catch (GoogleJsonResponseException e) {
                if (e.getStatusCode() == HISTORY_TOO_OLD) {
                    log.warn("historyId {} has aged out of Gmail retention — falling back to full sync", startHistoryId);
                    return Delta.fullSyncRequired("Stored historyId is older than Gmail's retention window.");
                }
                log.warn("history.list failed ({}): {}", e.getStatusCode(), e.getMessage());
                return new Delta(new ArrayList<>(added), null, true, false,
                    "history.list failed: HTTP " + e.getStatusCode());
            } catch (IOException | NumberFormatException e) {
                log.warn("history.list failed: {}", e.getMessage());
                return new Delta(new ArrayList<>(added), null, true, false,
                    "history.list failed: " + e.getMessage());
            }

            List<History> records = resp.getHistory();
            if (records != null) {
                for (History h : records) {
                    // Read the typed messagesAdded field, not History.getMessages(): the
                    // union field is documented as possibly containing duplicates.
                    List<HistoryMessageAdded> addedHere = h.getMessagesAdded();
                    if (addedHere == null) continue;
                    for (HistoryMessageAdded m : addedHere) {
                        if (m.getMessage() != null && m.getMessage().getId() != null) {
                            added.add(m.getMessage().getId());
                        }
                    }
                }
            }

            if (resp.getHistoryId() != null) latestHistoryId = resp.getHistoryId().toString();
            pageToken = resp.getNextPageToken();
            pages++;

            if (pages >= MAX_PAGES && pageToken != null) {
                // Bail out without advancing: a partial drain that moved the watermark would
                // skip the remainder for good. Next run resumes from the same point.
                log.warn("history.list exceeded {} pages; deferring the remainder to the next run", MAX_PAGES);
                return new Delta(new ArrayList<>(added), null, true, false,
                    "Page limit reached; watermark intentionally not advanced.");
            }
        } while (pageToken != null);

        // Empty delta: usually Gmail's index lagging the notification. Report it as complete
        // but carry no watermark, so the caller leaves the existing mark untouched.
        if (added.isEmpty() && latestHistoryId == null) {
            return new Delta(new ArrayList<>(), null, true, true,
                "No history records returned — index may lag the notification.");
        }

        return new Delta(new ArrayList<>(added), latestHistoryId, true, true,
            "Drained " + pages + " page(s); " + added.size() + " new message(s).");
    }

    /**
     * Chooses the watermark to persist, enforcing monotonicity.
     *
     * Notifications are at-least-once and can arrive out of order, so a candidate behind the
     * stored mark must be discarded — rewinding would re-import a window we already processed
     * and lean entirely on the fingerprint gate to undo it.
     *
     * @return the value to store, or null to leave the current watermark alone.
     */
    public String advanceWatermark(String current, String candidate) {
        if (candidate == null || candidate.trim().isEmpty()) return null;
        if (current == null || current.trim().isEmpty()) return candidate;
        try {
            BigInteger cur = new BigInteger(current.trim());
            BigInteger next = new BigInteger(candidate.trim());
            return next.compareTo(cur) > 0 ? candidate : null;
        } catch (NumberFormatException e) {
            log.warn("Unparseable historyId (current={}, candidate={}) — leaving watermark unchanged",
                current, candidate);
            return null;
        }
    }
}
