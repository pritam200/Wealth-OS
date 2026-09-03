package com.marketai.gmail.entity;

import lombok.*;
import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * An encrypted password saved after a successful manual unlock, keyed by the sender's
 * domain (providerKey) — reused automatically for future statements from the same
 * institution so the user is only ever asked once per provider, not once per statement.
 * The plaintext password is never stored; {@link #encryptedPassword} is AES-GCM ciphertext
 * (see com.marketai.gmail.security.PasswordCipher) and is never returned to the frontend.
 */
@Entity
@Table(name = "saved_pdf_passwords",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "provider_key"}),
       indexes = @Index(name = "idx_spp_user", columnList = "user_id"))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SavedPdfPassword {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "provider_key", nullable = false, length = 200)
    private String providerKey;

    @Column(name = "encrypted_password", nullable = false, length = 500)
    private String encryptedPassword;

    @Column(length = 300)
    private String passwordHint;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;
    private LocalDateTime lastUsedAt;
}
