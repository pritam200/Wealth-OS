package com.marketai.admin.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Metadata about a secret: when it was last rotated and by whom. Never the value. */
@Entity
@Table(name = "admin_secret_meta")
@Getter @Setter @NoArgsConstructor
public class AdminSecretMeta {
    @Id @Column(length = 80) private String name;
    private Instant lastRotatedAt;
    @Column(length = 150) private String rotatedBy;
}
