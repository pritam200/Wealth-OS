package com.marketai.document.entity;

import com.marketai.document.classify.ClassificationStage;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A single financial artifact in flight — an email body, a PDF attachment, an uploaded
 * statement — tracked from arrival to ledger.
 *
 * This exists because the ingestion path previously had no shared notion of "a document".
 * There were 18 parsers each with independent logic, a PendingPdf table for the encrypted-PDF
 * case, and no way to answer "what is in flight right now, and where is each thing stuck?"
 * Adding an issuer meant adding a class; nothing enumerated the population.
 *
 * It wraps rather than replaces the existing path: ProcessedEmail and PendingPdf keep working,
 * and this row records the lifecycle around them.
 *
 * <p><b>Status changes must go through {@link #transitionTo}</b>, which rejects illegal moves.
 * Setting the field directly bypasses the state machine — the same class of mistake the
 * Holding single-writer rule exists to prevent.
 */
@Entity
@Table(name = "financial_documents", indexes = {
    @Index(name = "idx_fd_user_status", columnList = "user_id, status"),
    @Index(name = "idx_fd_source_ref",  columnList = "user_id, source, source_ref", unique = true),
    @Index(name = "idx_fd_content",     columnList = "content_hash"),
    @Index(name = "idx_fd_status_seen", columnList = "status, first_seen_at")
})
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class FinancialDocument {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocumentSource source;

    /** Gmail message id, upload id, or CAS request id. Unique per user per source. */
    @Column(name = "source_ref", nullable = false, length = 200)
    private String sourceRef;

    /**
     * SHA-256 of the raw bytes. Identifies the <em>artifact</em> only — two different emails
     * describing the same trade have different content hashes, which is why economic identity
     * is resolved separately rather than by hashing.
     */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    @Builder.Default
    private DocumentStatus status = DocumentStatus.DISCOVERED;

    // --- classification outcome, flattened for queryability ---

    @Column(name = "doc_type", length = 60)
    private String docType;

    @Column(length = 80)
    private String issuer;

    @Column(name = "classification_confidence")
    private Double classificationConfidence;

    /** Which cascade stage produced the classification. A population dominated by MODEL means
     *  the cheap stages have stopped covering the common case — worth alerting on. */
    @Enumerated(EnumType.STRING)
    @Column(name = "matched_stage", length = 24)
    private ClassificationStage matchedStage;

    // --- lifecycle ---

    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt;

    @Column(name = "status_changed_at")
    private LocalDateTime statusChangedAt;

    @Column(name = "attempts", nullable = false)
    @Builder.Default
    private int attempts = 0;

    /** Why the last attempt failed. Never contains secrets — see AiAuditService redaction. */
    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @PrePersist
    void onCreate() {
        if (firstSeenAt == null) firstSeenAt = LocalDateTime.now();
        if (statusChangedAt == null) statusChangedAt = firstSeenAt;
        if (status == null) status = DocumentStatus.DISCOVERED;
    }

    /**
     * Moves to {@code next}, rejecting transitions the lifecycle does not allow.
     *
     * @throws IllegalStateException if the move is illegal — this is a programming error, not a
     *         data condition, so it fails loudly rather than silently coercing the status.
     */
    public void transitionTo(DocumentStatus next, String note) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException(
                "Illegal document transition " + status + " -> " + next + " (document id=" + id + ")");
        }
        // A retry starts the document over rather than resuming mid-flight: partial state left
        // behind by a failed run cannot be trusted, and re-deriving is cheap.
        if (status == DocumentStatus.FAILED && next == DocumentStatus.DISCOVERED) {
            attempts++;
        }
        this.status = next;
        this.statusChangedAt = LocalDateTime.now();
        this.lastError = (next == DocumentStatus.FAILED) ? note : null;
    }

    public void recordClassification(String docType, String issuer,
                                     double confidence, ClassificationStage stage) {
        this.docType = docType;
        this.issuer = issuer;
        this.classificationConfidence = confidence;
        this.matchedStage = stage;
    }
}
