package com.marketai.admin.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** One row per admin request. Path only: never the query string, headers or bodies. */
@Entity
@Table(name = "admin_request_log", indexes = {@Index(columnList = "occurredAt"), @Index(columnList = "sourceIp")})
@Getter @Setter @NoArgsConstructor
public class AdminRequestLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Instant occurredAt;
    @Column(length = 64) private String requestId;
    @Column(length = 64) private String sourceIp;
    @Column(length = 150) private String actorEmail;
    @Column(length = 10) private String method;
    @Column(length = 300) private String path;
    private int status;
    private long durationMs;
    @Column(length = 16) private String environment;
    @Column(length = 8) private String decision;
    @Column(length = 80) private String denyReason;
    @Column(length = 200) private String userAgent;
}
