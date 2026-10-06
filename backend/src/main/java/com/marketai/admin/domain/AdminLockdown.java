package com.marketai.admin.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Single row (id = 1). */
@Entity
@Table(name = "admin_lockdown")
@Getter @Setter @NoArgsConstructor
public class AdminLockdown {
    public static final long ID = 1L;
    @Id private Long id = ID;
    private boolean engaged;
    @Column(length = 150) private String engagedBy;
    private Instant engagedAt;
    @Column(length = 500) private String reason;
}
