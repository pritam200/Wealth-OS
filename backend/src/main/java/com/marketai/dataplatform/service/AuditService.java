package com.marketai.dataplatform.service;

import com.marketai.dataplatform.domain.LedgerAuditEvent;
import com.marketai.dataplatform.repo.LedgerAuditEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** Append-only record of every change a person or the system makes to the ledger. */
@Service
@RequiredArgsConstructor
public class AuditService {
    public static final String SYSTEM = "SYSTEM";
    private final LedgerAuditEventRepository repo;

    public void record(Long userId, String actor, String entityType, Long entityId, String action, String before, String after, String note) {
        repo.save(LedgerAuditEvent.builder().userId(userId).actor(actor == null ? SYSTEM : actor)
            .entityType(entityType).entityId(entityId).action(action)
            .before(cut(before)).after(cut(after)).note(cut(note)).build());
    }

    public List<LedgerAuditEvent> history(String entityType, Long entityId) {
        return repo.findByEntityTypeAndEntityIdOrderByOccurredAtAscIdAsc(entityType, entityId);
    }

    private static String cut(String s) { return s == null || s.length() <= 500 ? s : s.substring(0, 500); }
}
