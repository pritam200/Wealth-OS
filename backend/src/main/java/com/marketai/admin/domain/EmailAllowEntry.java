package com.marketai.admin.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "admin_email_allowlist", indexes = @Index(columnList = "emailOrDomain"))
@Getter @Setter @NoArgsConstructor
public class EmailAllowEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 150) private String emailOrDomain;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private EntryKind kind;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private AdminRole role;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private EntryStatus status = EntryStatus.ACTIVE;
    @Column(length = 150) private String createdBy;
    private Instant createdAt;
    @Column(length = 150) private String updatedBy;
    private Instant updatedAt;
    private Instant expiresAt;

    public boolean isLive(Instant now) {
        return status == EntryStatus.ACTIVE && (expiresAt == null || expiresAt.isAfter(now));
    }
}
