package com.marketai.gmail.entity;

import lombok.*;
import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * A financial-looking email that carried a password-protected (or otherwise unread) PDF
 * attachment and produced no imported transactions from its body text — instead of silently
 * dropping it, it's queued here so the user can supply the password once. On successful
 * unlock the password is saved (encrypted, see SavedPdfPassword) per sender/provider so future
 * statements from the same institution unlock automatically without asking again.
 */
@Entity
@Table(name = "pending_pdfs",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "gmail_message_id", "attachment_id"}),
       indexes = @Index(name = "idx_ppdf_user_status", columnList = "user_id, status"))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PendingPdf {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "gmail_message_id", nullable = false)
    private String gmailMessageId;

    @Column(name = "attachment_id", nullable = false, length = 500)
    private String attachmentId;

    private String filename;
    private String sender;

    @Column(length = 500)
    private String subject;

    // Normalized sender domain (e.g. "mstock.com") — the key used to look up/save a
    // reusable password, since a given institution's statements always come from the
    // same domain even when the account holder differs.
    @Column(length = 200)
    private String providerKey;

    // Best-effort guess at the password format, extracted from the email body (e.g. "PAN
    // (uppercase) + Date of Birth DDMMYYYY"). Null when it can't be determined — shown to
    // the user as "format unknown" rather than silently omitted.
    @Column(length = 300)
    private String passwordHint;

    @Builder.Default
    @Column(length = 20)
    private String status = "NEEDS_PASSWORD"; // NEEDS_PASSWORD | PASSWORD_FAILED | IMPORTED | FAILED | DISMISSED

    @Column(length = 500)
    private String resultSummary;

    // Per-step pipeline trace stored as JSON array — each element is a step object with
    // {step, status, detail}. Updated by PdfImportService.attemptUnlock() so the Contract
    // Note Debug View can show exactly where the pipeline succeeded or failed.
    @Column(columnDefinition = "TEXT")
    private String pipelineSteps;

    private Integer tradesExtracted;
    private Integer tradesImported;

    @Column(columnDefinition = "TEXT")
    private String textSnippet;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime unlockedAt;
}
