package com.marketai.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketai.admin.domain.AdminAuditEvent;
import com.marketai.admin.repo.AdminAuditRepository;
import com.marketai.admin.security.AdminContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * Append-only, hash-chained audit trail: each event's hash covers the previous event's hash, so
 * editing or removing any past row breaks every hash after it and {@link #verify()} reports where.
 * Writes are serialised in-process (single instance) and run in their own transaction so an audit
 * row is committed even when the operation around it fails.
 */
@Service
public class AdminAuditService {

    private static final char SEP = '\u001F';
    private final AdminAuditRepository repo;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public AdminAuditService(AdminAuditRepository repo, ObjectMapper mapper, PlatformTransactionManager tm) {
        this.repo = repo;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(tm);
        this.tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public AdminAuditEvent record(AdminContext ctx, String action, String targetType, String targetId, String operation,
                                  Map<String, ?> before, Map<String, ?> after, String reason) {
        return append(ctx == null ? null : ctx.email(), ctx == null ? null : ctx.ip(), ctx == null ? null : ctx.requestId(),
            ctx == null ? null : ctx.environment().name(), action, targetType, targetId, operation, before, after, reason);
    }

    /** For events with no authenticated caller (a denied attempt). */
    public AdminAuditEvent append(String actorEmail, String actorIp, String requestId, String environment, String action,
                                  String targetType, String targetId, String operation,
                                  Map<String, ?> before, Map<String, ?> after, String reason) {
        String b = json(Redactor.redact(before)), a = json(Redactor.redact(after));
        synchronized (this) {
            return tx.execute(s -> {
                String prev = repo.findTopByOrderByIdDesc().map(AdminAuditEvent::getEventHash).orElse(null);
                Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
                AdminAuditEvent e = AdminAuditEvent.builder()
                    .occurredAt(now).actorEmail(Redactor.clean(actorEmail)).actorIp(Redactor.clean(actorIp))
                    .requestId(Redactor.clean(requestId)).action(action).targetType(targetType).targetId(Redactor.clean(targetId))
                    .operation(operation).beforeValue(b).afterValue(a).reason(Redactor.clean(reason)).environment(environment)
                    .prevHash(prev).build();
                return repo.save(withHash(e, prev));
            });
        }
    }

    public Page<AdminAuditEvent> page(int page, int size) {
        return repo.findAllByOrderByIdDesc(PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200)));
    }

    public record Verification(boolean valid, long checked, Long firstBadId, String message) {}

    /** Walks the whole chain in id order and recomputes every hash. */
    public Verification verify() {
        String prev = null; long checked = 0; int page = 0;
        while (true) {
            Slice<AdminAuditEvent> slice = repo.findAllByOrderByIdAsc(PageRequest.of(page++, 500));
            for (AdminAuditEvent e : slice) {
                checked++;
                boolean linkOk = java.util.Objects.equals(prev, e.getPrevHash());
                boolean hashOk = hash(e, e.getPrevHash()).equals(e.getEventHash());
                if (!linkOk || !hashOk) {
                    return new Verification(false, checked, e.getId(),
                        !linkOk ? "The chain is broken here: an earlier event was removed or reordered." : "This event was modified after it was written.");
                }
                prev = e.getEventHash();
            }
            if (!slice.hasNext()) break;
        }
        return new Verification(true, checked, null, checked == 0 ? "No events yet." : "Chain intact.");
    }

    private AdminAuditEvent withHash(AdminAuditEvent e, String prev) {
        return AdminAuditEvent.builder().occurredAt(e.getOccurredAt()).actorEmail(e.getActorEmail()).actorIp(e.getActorIp())
            .requestId(e.getRequestId()).action(e.getAction()).targetType(e.getTargetType()).targetId(e.getTargetId())
            .operation(e.getOperation()).beforeValue(e.getBeforeValue()).afterValue(e.getAfterValue()).reason(e.getReason())
            .environment(e.getEnvironment()).prevHash(prev).eventHash(hash(e, prev)).build();
    }

    static String hash(AdminAuditEvent e, String prev) {
        String[] f = {prev, String.valueOf(e.getOccurredAt().toEpochMilli()), e.getActorEmail(), e.getActorIp(), e.getRequestId(),
            e.getAction(), e.getTargetType(), e.getTargetId(), e.getOperation(), e.getBeforeValue(), e.getAfterValue(),
            e.getReason(), e.getEnvironment()};
        StringBuilder sb = new StringBuilder();
        for (String s : f) sb.append(s == null ? "" : s).append(SEP);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private String json(Object o) {
        if (o == null) return null;
        try {
            String s = mapper.writeValueAsString(o);
            return s.length() > 4000 ? s.substring(0, 3990) + "…\"}" : s;
        } catch (Exception e) { return "{\"error\":\"unserialisable\"}"; }
    }

    Optional<AdminAuditEvent> last() { return repo.findTopByOrderByIdDesc(); }
}
