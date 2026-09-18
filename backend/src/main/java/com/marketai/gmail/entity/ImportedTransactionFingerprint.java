package com.marketai.gmail.entity;

import lombok.*;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * One row per financial record ever imported from an email/PDF, keyed by a SHA-256 hash of the
 * transaction's own identifying content (type + date + amount + symbol/folio/bank + quantity)
 * rather than by the Gmail message id.
 *
 * Why this exists on top of the per-domain content checks in ParsedEmailImporter: those checks
 * are each written against one table's columns, so a new parser or import path can miss one and
 * silently double-book. This is a single uniform gate every import passes through, and the
 * (user_id, fingerprint) unique constraint makes the guarantee a DB-level one — a resent or
 * forwarded email carrying an already-imported transaction cannot create a second record even if
 * it arrives with a brand-new Gmail message id.
 */
@Entity
@Table(name = "imported_transaction_fingerprints",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "fingerprint"}),
       indexes = {
           @Index(name = "idx_itf_user_fp", columnList = "user_id, fingerprint"),
           @Index(name = "idx_itf_user_extref", columnList = "user_id, external_ref")
       })
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ImportedTransactionFingerprint {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 64)
    private String fingerprint;

    // Parsed transaction type (TRADE_BUY, DIVIDEND, EXPENSE, ...) — for auditability only;
    // dedup is on the fingerprint alone.
    @Column(length = 40)
    private String type;

    @Column(name = "gmail_message_id", length = 100)
    private String gmailMessageId;

    @Column(length = 300)
    private String description;

    /**
     * Strongest globally-unique rail reference carried by the source document — a UTR, RRN,
     * UPI transaction id or IMPS reference.
     *
     * <p>Stored because it identifies the same payment across documents that share no other
     * field. The content fingerprint above cannot match a bank debit against a contract note
     * for one trade (different amounts, different dates, different bytes); the rail reference
     * can, exactly.
     *
     * <p>Only globally-unique types are ever stored here. A cheque number repeats across
     * accounts and two brokers can each mint order "12345", so issuer-scoped references are
     * deliberately excluded — matching on one would merge unrelated transactions.
     */
    @Column(name = "external_ref", length = 64)
    private String externalRef;

    /** Which rail issued {@link #externalRef}. Kept so a match can explain itself. */
    @Column(name = "external_ref_type", length = 20)
    private String externalRefType;

    /**
     * The row this one restates, when a later document corrects an earlier one.
     *
     * <p>Following Plaid's pending-to-posted precedent: a restatement is a <em>link</em>, never
     * an overwrite. Both the original and the correction stay readable, which is what
     * "never overwrite financial records" requires.
     */
    @Column(name = "supersedes_id")
    private Long supersedesId;

    /**
     * Set when a later document reported the same rail-issued payment with different financial
     * content — the same UTR, a different amount or date.
     *
     * <p>Previously this case was silently skipped: the reference matched, the importer returned,
     * and nothing recorded that two sources disagreed about one payment. Skipping is the right
     * call for the ledger — booking the second version would double-count, and overwriting the
     * first would destroy an already-verified record — but doing it silently means a genuine
     * discrepancy (a restatement, a correction, a parser error on one side) is invisible.
     *
     * <p>So the conflicting reading is recorded against the original rather than discarded, and
     * surfaced through reconciliation for a human to resolve.
     */
    @Column(name = "conflict_detected", nullable = false, columnDefinition = "boolean not null default false")
    @Builder.Default
    private boolean conflictDetected = false;

    /** What differed, in plain words. Never contains a raw document or a secret. */
    @Column(name = "conflict_detail", length = 500)
    private String conflictDetail;

    @Column(nullable = false, updatable = false)
    private LocalDateTime importedAt;

    @PrePersist
    void onCreate() { if (importedAt == null) importedAt = LocalDateTime.now(); }
}
