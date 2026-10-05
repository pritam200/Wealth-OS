package com.marketai.dataplatform.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** The consent lifecycle for an aggregator connection. Consent artefacts and tokens are never stored. */
@Entity
@Table(name = "consent_records", indexes = @Index(name = "idx_cr_user", columnList = "user_id"))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ConsentRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "connection_id") private Long connectionId;
    @Column(name = "provider_id", nullable = false, length = 80) private String providerId;
    /** The provider's identifier for the consent — a handle, not a credential. */
    @Column(name = "provider_consent_handle", length = 200) private String providerConsentHandle;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private ConsentStatus status;
    @Column(length = 300) private String purpose;
    @Column(name = "fi_types", length = 300) private String fiTypes;
    @Column(name = "data_range_from") private LocalDate dataRangeFrom;
    @Column(name = "data_range_to") private LocalDate dataRangeTo;
    @Column(name = "expires_at") private LocalDateTime expiresAt;
    @Column(name = "requested_at", nullable = false) private LocalDateTime requestedAt;
    @Column(name = "approved_at") private LocalDateTime approvedAt;
    @Column(name = "revoked_at") private LocalDateTime revokedAt;
    @Column(name = "last_event_at") private LocalDateTime lastEventAt;
}
