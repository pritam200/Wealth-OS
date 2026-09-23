package com.marketai.gmail.entity;

import lombok.*;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * An encrypted password saved after a successful manual unlock, keyed by the sender's
 * domain (providerKey) plus document type — reused automatically for future statements of
 * that same kind from the same institution so the user is only ever asked once per
 * provider/document-type combination, not once per statement.
 *
 * <p>{@link #documentType} exists because one institution routinely uses different password
 * conventions for different statement kinds (e.g. a card statement using last-4-digits+DOB
 * vs. a bank account statement using a different scheme) — keying on domain alone meant
 * learning one type's password silently overwrote whatever had been learned for the other,
 * and the sibling-auto-unlock sweep in {@code PdfImportService.unlock} would then try that
 * password against every other queued PDF from the same sender regardless of kind. Null
 * (document type unknown/unclassified) is its own distinct bucket, matching the pre-existing
 * behaviour for rows that predate this column.
 *
 * <p>The plaintext password is never stored; {@link #encryptedPassword} is AES-GCM ciphertext
 * (see com.marketai.gmail.security.PasswordCipher) and is never returned to the frontend.
 */
@Entity
@Table(name = "saved_pdf_passwords",
       // Widening (user_id, provider_key) to include document_type can only relax the
       // constraint, never violate it against existing data — every pre-existing row was
       // already unique on the first two columns alone.
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "provider_key", "document_type"}),
       indexes = @Index(name = "idx_spp_user", columnList = "user_id"))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SavedPdfPassword {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "provider_key", nullable = false, length = 200)
    private String providerKey;

    // One of com.marketai.document.classify.DocTypes, or null when the PDF predates this
    // column or couldn't be classified from its subject line.
    @Column(name = "document_type", length = 40)
    private String documentType;

    @Column(name = "encrypted_password", nullable = false, length = 500)
    private String encryptedPassword;

    @Column(length = 300)
    private String passwordHint;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;
    private LocalDateTime lastUsedAt;
}
