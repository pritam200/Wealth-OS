package com.marketai.inbound;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A user's private forwarding address: {@code import-<code>@<inbound domain>}. The code is the only
 * thing identifying whose statements these are, so it is random and can be rotated if it leaks.
 */
@Entity
@Table(name = "inbound_addresses", uniqueConstraints = {
    @UniqueConstraint(name = "uk_inbound_user", columnNames = "user_id"),
    @UniqueConstraint(name = "uk_inbound_code", columnNames = "code")})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class InboundAddress {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(nullable = false, length = 24) private String code;
    /** The user's CAS password, AES-GCM encrypted (PasswordCipher) — lets forwarded PDFs open unattended. */
    @Column(name = "cas_password_enc", columnDefinition = "text") private String casPasswordEncrypted;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
}
