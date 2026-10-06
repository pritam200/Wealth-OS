package com.marketai.admin.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "admin_ip_allowlist", indexes = @Index(columnList = "environment,status"))
@Getter @Setter @NoArgsConstructor
public class IpAllowEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 64) private String cidr;
    @Column(length = 200) private String description;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private AdminEnvironment environment;
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
