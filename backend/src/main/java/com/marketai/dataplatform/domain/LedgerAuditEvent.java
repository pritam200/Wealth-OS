package com.marketai.dataplatform.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Who changed what in the ledger and why. Appended to, never edited. */
@Entity
@Table(name = "ledger_audit_events", indexes = @Index(name = "idx_lae_entity", columnList = "entity_type, entity_id"))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class LedgerAuditEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    /** The acting user id, or SYSTEM. */
    @Column(nullable = false, length = 20) private String actor;
    @Column(name = "entity_type", nullable = false, length = 40) private String entityType;
    @Column(name = "entity_id") private Long entityId;
    @Column(nullable = false, length = 40) private String action;
    @Column(length = 500) private String before;
    @Column(length = 500) private String after;
    @Column(length = 500) private String note;
    @Column(name = "occurred_at", nullable = false) private LocalDateTime occurredAt;
    @PrePersist void onCreate() { if (occurredAt == null) occurredAt = LocalDateTime.now(); }
}
