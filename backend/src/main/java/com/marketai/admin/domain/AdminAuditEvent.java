package com.marketai.admin.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/** Append-only. Hibernate never issues UPDATE for it; the database triggers in database/admin_audit_immutability.sql forbid it too. */
@Entity
@Immutable
@Table(name = "admin_audit_events")
@Getter @Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@lombok.AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AdminAuditEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, updatable = false) private Instant occurredAt;
    @Column(length = 150, updatable = false) private String actorEmail;
    @Column(length = 64, updatable = false) private String actorIp;
    @Column(length = 64, updatable = false) private String requestId;
    @Column(nullable = false, length = 80, updatable = false) private String action;
    @Column(length = 60, updatable = false) private String targetType;
    @Column(length = 150, updatable = false) private String targetId;
    @Column(length = 40, updatable = false) private String operation;
    @Column(length = 4000, updatable = false) private String beforeValue;
    @Column(length = 4000, updatable = false) private String afterValue;
    @Column(length = 500, updatable = false) private String reason;
    @Column(length = 16, updatable = false) private String environment;
    @Column(length = 64, updatable = false) private String prevHash;
    @Column(nullable = false, length = 64, updatable = false) private String eventHash;
}
