package com.marketai.admin.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** One authenticator per admin. The secret is stored encrypted (AES-GCM) and never returned after enrolment. */
@Entity
@Table(name = "admin_mfa")
@Getter @Setter @NoArgsConstructor
public class AdminMfa {
    @Id @Column(length = 150) private String email;
    @Column(nullable = false, length = 500) private String secretEnc;
    private boolean enabled;
    private long lastCounter;
    private Instant createdAt;
}
