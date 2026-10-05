package com.marketai.dataplatform.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A user's link to a data source. Holds no credentials: authorisation happens at the provider
 * (bank, aggregator, broker), and only the consent handle and a sync cursor are kept here.
 */
@Entity
@Table(name = "data_connections", indexes = @Index(name = "idx_dc_user", columnList = "user_id"))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DataConnection {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "provider_id", nullable = false, length = 80) private String providerId;
    @Enumerated(EnumType.STRING) @Column(name = "source_type", nullable = false, length = 20) private SourceType sourceType;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private ProviderMode mode;
    @Column(length = 120) private String institution;
    @Column(name = "display_name", length = 200) private String displayName;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    @Builder.Default private ConnectionStatus status = ConnectionStatus.PENDING_CONSENT;
    @Column(name = "consent_id") private Long consentId;
    @Column(name = "sync_cursor", length = 300) private String syncCursor;
    @Column(name = "last_sync_at") private LocalDateTime lastSyncAt;
    @Column(name = "last_successful_sync_at") private LocalDateTime lastSuccessfulSyncAt;
    @Enumerated(EnumType.STRING) @Column(name = "sync_status", nullable = false, length = 20)
    @Builder.Default private SyncStatus syncStatus = SyncStatus.IDLE;
    @Column(name = "last_error", length = 500) private String lastError;
    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @PrePersist void onCreate() { createdAt = LocalDateTime.now(); }
}
