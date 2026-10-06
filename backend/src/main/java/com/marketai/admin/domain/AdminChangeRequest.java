package com.marketai.admin.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** A change to a sensitive field, waiting for a second administrator. */
@Entity
@Table(name = "admin_change_request")
@Getter @Setter @NoArgsConstructor
public class AdminChangeRequest {
    public enum Status { PENDING, APPROVED, REJECTED, FAILED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 60) private String tableName;
    @Column(nullable = false, length = 60) private String recordId;
    @Column(nullable = false, length = 2000) private String changesJson;
    @Column(length = 500) private String reason;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 12) private Status status = Status.PENDING;
    @Column(length = 150) private String requestedBy;
    private Instant requestedAt;
    @Column(length = 150) private String decidedBy;
    private Instant decidedAt;
    @Column(length = 300) private String outcome;
}
