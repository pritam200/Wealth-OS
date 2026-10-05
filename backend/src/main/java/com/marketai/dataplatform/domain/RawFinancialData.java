package com.marketai.dataplatform.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * What a source delivered, kept as received, before it is interpreted. Normalisation can be
 * re-run from this. The payload is encrypted at rest and never holds credentials, OTPs or PINs.
 */
@Entity
@Table(name = "raw_financial_data",
    uniqueConstraints = @UniqueConstraint(name = "uk_raw_user_idem", columnNames = {"user_id", "idempotency_key"}),
    indexes = {@Index(name = "idx_raw_user_status", columnList = "user_id, processing_status"),
               @Index(name = "idx_raw_run", columnList = "sync_run_id")})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class RawFinancialData {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "connection_id") private Long connectionId;
    @Column(name = "sync_run_id") private Long syncRunId;

    @Enumerated(EnumType.STRING) @Column(name = "source_type", nullable = false, length = 20) private SourceType sourceType;
    @Column(name = "source_provider", nullable = false, length = 80) private String sourceProvider;
    @Enumerated(EnumType.STRING) @Column(name = "provider_mode", nullable = false, length = 10) private ProviderMode providerMode;
    @Enumerated(EnumType.STRING) @Column(name = "record_kind", nullable = false, length = 20) private RecordKind recordKind;

    @Column(name = "external_account_id", length = 120) private String externalAccountId;
    @Column(name = "external_record_id", length = 200) private String externalRecordId;

    /** The payload, AES-GCM encrypted. */
    @Column(columnDefinition = "text") private String payload;
    @Column(name = "payload_hash", nullable = false, length = 64) private String payloadHash;
    /** Hash of source, provider, record id (or payload hash), account and kind — one record is processed once. */
    @Column(name = "idempotency_key", nullable = false, length = 64) private String idempotencyKey;
    @Column(name = "schema_version", length = 40) private String schemaVersion;

    @Column(name = "received_at", nullable = false) private LocalDateTime receivedAt;
    @Enumerated(EnumType.STRING) @Column(name = "processing_status", nullable = false, length = 30)
    @Builder.Default private ProcessingStatus processingStatus = ProcessingStatus.RECEIVED;
    @Column(length = 1000) private String error;
    @Column(name = "processed_at") private LocalDateTime processedAt;
}
