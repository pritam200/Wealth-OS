package com.marketai.gmail.ledger;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Keeps {@link EmailFinancialEvent}: one row per financial event found in an email, and per part
 * of an email that could not be read, each with where it ended up and why.
 *
 * <p>Rules for a re-read of the same event: a person's decision is never overridden, and an
 * event already booked stays IMPORTED (the re-read finding it "already recorded" is that same
 * record). Anything else takes the latest reading. Rows are never deleted.
 *
 * <p>A failure to write here never stops an import; it is logged and counted, and the sync
 * reports it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FinancialEventLedger {

    private final EmailFinancialEventRepository repo;

    /** Ledger writes that failed during the current run on this thread. */
    private static final ThreadLocal<int[]> FAILURES = ThreadLocal.withInitial(() -> new int[1]);

    public static void resetFailures() { FAILURES.get()[0] = 0; }

    public static int failures() { return FAILURES.get()[0]; }

    public void record(EmailFinancialEvent incoming) {
        if (incoming == null || incoming.getUserId() == null || incoming.getGmailMessageId() == null) return;
        try {
            LocalDateTime now = LocalDateTime.now();
            incoming.setEvidence(mask(truncate(incoming.getEvidence(), 480)));
            incoming.setReason(truncate(incoming.getReason(), 1000));
            incoming.setMerchant(truncate(incoming.getMerchant(), 200));
            incoming.setInstrument(truncate(incoming.getInstrument(), 200));
            incoming.setAccount(mask(truncate(incoming.getAccount(), 100)));
            incoming.setReference(truncate(incoming.getReference(), 100));
            incoming.setEventType(truncate(incoming.getEventType(), 60));
            incoming.setStatementProvider(truncate(incoming.getStatementProvider(), 120));
            incoming.setAttachmentName(truncate(incoming.getAttachmentName(), 255));
            EmailFinancialEvent existing = repo.findByUserIdAndGmailMessageIdAndEventKey(
                incoming.getUserId(), incoming.getGmailMessageId(), incoming.getEventKey()).orElse(null);
            if (existing == null) {
                incoming.setId(null);
                incoming.setFirstSeenAt(now);
                incoming.setLastSeenAt(now);
                if (incoming.getResolvedBy() == null) incoming.setResolvedBy(EmailFinancialEvent.BY_SYSTEM);
                repo.save(incoming);
                return;
            }
            existing.setLastSeenAt(now);
            boolean keep = EmailFinancialEvent.BY_USER.equals(existing.getResolvedBy())
                || (existing.getState() == EventState.IMPORTED && incoming.getState() != EventState.IMPORTED);
            if (!keep) {
                incoming.setId(existing.getId());
                incoming.setFirstSeenAt(existing.getFirstSeenAt());
                incoming.setLastSeenAt(now);
                incoming.setResolvedBy(EmailFinancialEvent.BY_SYSTEM);
                repo.save(incoming);
            } else {
                repo.save(existing);
            }
        } catch (Exception e) {
            FAILURES.get()[0]++;
            log.warn("Could not record financial event {} for message {}: {}",
                incoming.getEventKey(), incoming.getGmailMessageId(), e.getMessage());
        }
    }

    /**
     * Closes an email-level row (an unread part, a totals mismatch) once a later read no longer
     * reports the problem. Nothing happens when there is no such row, or a person already decided it.
     */
    public void resolveIfOpen(Long userId, String gmailMessageId, String eventKey, String reason) {
        if (userId == null || gmailMessageId == null) return;
        try {
            repo.findByUserIdAndGmailMessageIdAndEventKey(userId, gmailMessageId, eventKey).ifPresent(row -> {
                if (!row.getState().unresolved() || EmailFinancialEvent.BY_USER.equals(row.getResolvedBy())) return;
                row.setState(EventState.RESOLVED);
                row.setReason(truncate(reason, 1000));
                row.setLastSeenAt(LocalDateTime.now());
                repo.save(row);
            });
        } catch (Exception e) {
            FAILURES.get()[0]++;
            log.warn("Could not close financial event {} for message {}: {}", eventKey, gmailMessageId, e.getMessage());
        }
    }

    /** A person decided the review item: the events behind it are accounted for. */
    public void onReviewDecision(Long userId, String gmailMessageId, int itemIndex, boolean booked, String note) {
        if (userId == null || gmailMessageId == null) return;
        try {
            for (EmailFinancialEvent row : repo.findByUserIdAndGmailMessageIdAndItemIndex(userId, gmailMessageId, itemIndex)) {
                if (!row.getState().unresolved()) continue;
                row.setState(booked ? EventState.IMPORTED : EventState.RESOLVED);
                row.setReason(truncate((booked ? "Accepted in the review queue" : "Rejected in the review queue")
                    + (note != null && !note.isBlank() ? ": " + note : "."), 1000));
                row.setResolvedBy(EmailFinancialEvent.BY_USER);
                row.setLastSeenAt(LocalDateTime.now());
                repo.save(row);
            }
        } catch (Exception e) {
            // The decision itself stands; the ledger row is corrected on the email's next read.
            log.warn("Could not record the review decision for message {} item {}: {}", gmailMessageId, itemIndex, e.getMessage());
        }
    }

    /**
     * A person checked an email-level problem (a totals mismatch, a conflict, an attachment that
     * could not be read) and marked it dealt with. Events waiting in the review queue are
     * decided there instead, where accepting one books it.
     */
    public EmailFinancialEvent acknowledge(Long userId, Long id, String note) {
        EmailFinancialEvent row = repo.findById(id).filter(r -> r.getUserId().equals(userId))
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Event not found"));
        if (row.getState() != EventState.RECONCILIATION_REQUIRED) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                row.getState().unresolved() ? "Decide this one in the review queue — accepting it there books it."
                    : "This event is already accounted for.");
        }
        row.setState(EventState.RESOLVED);
        row.setResolvedBy(EmailFinancialEvent.BY_USER);
        row.setReason(truncate("Checked and marked resolved" + (note != null && !note.isBlank() ? ": " + note.trim() : "")
            + " (was: " + row.getReason() + ")", 1000));
        row.setLastSeenAt(LocalDateTime.now());
        return repo.save(row);
    }

    public List<EmailFinancialEvent> forEmail(Long userId, String gmailMessageId) {
        return repo.findByUserIdAndGmailMessageIdOrderByIdAsc(userId, gmailMessageId);
    }

    public List<EmailFinancialEvent> unresolved(Long userId, int limit) {
        return repo.findByUserIdAndStateInOrderByLastSeenAtDesc(userId, EventState.unresolvedStates(),
            PageRequest.of(0, Math.max(1, Math.min(limit, 1000))));
    }

    /** Every event on record for the user, by state, plus how many emails still hold an unresolved one. */
    public Coverage coverage(Long userId) {
        return new Coverage(byState(repo.countByState(userId)),
            repo.countEmailsByUserIdAndStateIn(userId, EventState.unresolvedStates()));
    }

    /** Events read or re-read since {@code since} (a sync run), by state. */
    public Map<EventState, Long> seenSince(Long userId, LocalDateTime since) {
        return byState(repo.countByStateSeenSince(userId, since));
    }

    public record Coverage(Map<EventState, Long> byState, long emailsWithUnresolved) {
        public long total() { return byState.values().stream().mapToLong(Long::longValue).sum(); }
        public long unresolved() { return unresolvedIn(byState); }
    }

    public static long unresolvedIn(Map<EventState, Long> byState) {
        return byState.entrySet().stream().filter(e -> e.getKey().unresolved()).mapToLong(Map.Entry::getValue).sum();
    }

    private static Map<EventState, Long> byState(List<Object[]> rows) {
        Map<EventState, Long> out = new EnumMap<>(EventState.class);
        for (EventState s : EventState.values()) out.put(s, 0L);
        for (Object[] r : rows) out.put((EventState) r[0], ((Number) r[1]).longValue());
        return out;
    }

    // Card numbers, account numbers and PAN are masked before a source line is stored: the
    // ledger needs the line to show why an event is where it is, not the identifiers in it.
    private static final Pattern CARD = Pattern.compile("\\b\\d{4}[ -]\\d{4}[ -]\\d{4}[ -]\\d{1,7}\\b");
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d{9,}");
    private static final Pattern PAN = Pattern.compile("\\b[A-Z]{5}\\d{4}[A-Z]\\b");

    static String mask(String s) {
        if (s == null) return null;
        String out = CARD.matcher(s).replaceAll(m -> "••••" + last4(m.group()));
        out = LONG_DIGITS.matcher(out).replaceAll(m -> "••••" + last4(m.group()));
        return PAN.matcher(out).replaceAll("••••••••••");
    }

    private static String last4(String digits) {
        String d = digits.replaceAll("\\D", "");
        return d.length() <= 4 ? d : d.substring(d.length() - 4);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
