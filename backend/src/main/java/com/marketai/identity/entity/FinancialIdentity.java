package com.marketai.identity.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * The user's PAN and date of birth, encrypted at rest, used solely to derive passwords for
 * locked financial statements.
 *
 * <p>This exists because the system previously knew <em>what</em> a statement password was
 * without being able to produce it. {@code PasswordHintExtractor} reads an email body and
 * correctly concludes "the password is your PAN in uppercase" — but no PAN was stored anywhere
 * in the application, so a first-time user with no manually-saved passwords could never
 * auto-unlock anything. Every locked statement stayed locked until typed in by hand.
 *
 * <p><b>Security posture, non-negotiable:</b>
 * <ul>
 *   <li>Both values are encrypted with {@code PasswordCipher} (AES-GCM) before persistence.
 *       Plaintext never touches the database.</li>
 *   <li>Neither value is ever returned by an API. {@link com.marketai.identity.dto.FinancialIdentityStatus}
 *       exposes only whether a value is present, never the value.</li>
 *   <li>Neither value is ever written to a log — not at DEBUG, not in an exception message.</li>
 *   <li>Values are read only inside password derivation, held in a local variable, and never
 *       propagated into a DTO, an audit trail, or an LLM prompt.</li>
 * </ul>
 *
 * <p>PAN is a government identifier and DOB is sensitive personal data; both are exactly the
 * kind of field the project's PII rules require be stripped before any payload reaches a
 * fallback LLM parser.
 */
@Entity
@Table(name = "financial_identities",
       uniqueConstraints = @UniqueConstraint(columnNames = "user_id"))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class FinancialIdentity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    /** AES-GCM ciphertext of the PAN, uppercased before encryption. Never plaintext. */
    @Column(name = "encrypted_pan", length = 500)
    private String encryptedPan;

    /** AES-GCM ciphertext of the date of birth, stored as ISO yyyy-MM-dd. Never plaintext. */
    @Column(name = "encrypted_dob", length = 500)
    private String encryptedDob;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** When a derived password last successfully opened a document. Diagnostic only. */
    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }

    public boolean hasPan() { return encryptedPan != null && !encryptedPan.isBlank(); }
    public boolean hasDob() { return encryptedDob != null && !encryptedDob.isBlank(); }
}
