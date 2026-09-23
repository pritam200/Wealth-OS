package com.marketai.gmail.entity;

import lombok.*;
import jakarta.persistence.*;
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
       indexes = {
           @Index(name = "idx_ppdf_user_status", columnList = "user_id, status"),
           @Index(name = "idx_ppdf_user_content", columnList = "user_id, content_hash")
       })
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

    // Best-effort guess from the subject line (see SubjectPatternStage), one of
    // com.marketai.document.classify.DocTypes, or null when unclassifiable. Lets password
    // learning distinguish "this is a card-statement password" from "this is a bank-statement
    // password" for the same sender domain, instead of treating every statement from an
    // institution as needing the same password.
    @Column(name = "document_type", length = 40)
    private String documentType;

    // Best-effort guess at the password format, extracted from the email body (e.g. "PAN
    // (uppercase) + Date of Birth DDMMYYYY"). Null when it can't be determined — shown to
    // the user as "format unknown" rather than silently omitted.
    @Column(length = 300)
    private String passwordHint;

    /**
     * SHA-256 of the decrypted document bytes — the attachment's content identity.
     *
     * <p>The table's unique constraint is (user, gmail_message_id, attachment_id), which only
     * catches the same attachment on the same message. It does not catch the same statement
     * arriving as a forward (new message id), re-sent by the provider, or attached to two
     * threads — and Gmail is documented to return a different attachment_id for the same
     * physical attachment across fetches, which is why the lookup code had already fallen back
     * to matching on filename. Filename is no better: providers name statements
     * "Statement.pdf" every month.
     *
     * <p>Content hashing is the only identifier that survives all of those, so a document that
     * has already been parsed is recognised as DUPLICATE_DOCUMENT rather than re-imported.
     *
     * <p>Nullable: rows created before this column existed, and rows still locked (bytes never
     * successfully decrypted), legitimately have no hash.
     */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

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
