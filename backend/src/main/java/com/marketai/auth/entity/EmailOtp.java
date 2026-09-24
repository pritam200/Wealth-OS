package com.marketai.auth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One requested verification code for one email address. The code is stored only as its
 * BCrypt hash — {@link com.marketai.auth.service.EmailOtpService} never persists or logs
 * the plaintext code, mirroring how {@link User#password} is handled.
 *
 * A row also carries the post-verification state: once {@link #consumed} flips to true,
 * {@link #verificationToken} is the one-time credential the actual {@code /register} call
 * must present, so registration cannot be completed without ever having called
 * {@code verify-otp} — POSTing straight to {@code /register} is not a bypass.
 */
@Entity
@Table(name = "email_otps")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class EmailOtp {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String email;

    @Column(name = "code_hash", nullable = false, length = 100)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Builder.Default
    @Column(nullable = false)
    private int attempts = 0;

    @Builder.Default
    @Column(nullable = false)
    private boolean consumed = false;

    @Column(name = "verification_token", length = 40, unique = true)
    private String verificationToken;

    @Column(name = "verification_token_expires_at")
    private Instant verificationTokenExpiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
