package com.marketai.ai.review.entity;

import lombok.*;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * An email whose financial content could not be booked with enough confidence to import
 * automatically.
 *
 * This table exists because the previous behaviour was to drop such emails with a log line —
 * safe, in that nothing wrong was written, but lossy: a real trade could go unimported with no
 * trace the user could see. Every uncertain extraction now becomes a visible row a human can
 * accept, correct, or reject.
 *
 * One row per (user, gmail message, item index) so re-syncing the same email doesn't stack up
 * duplicates in the queue — but a single email carrying several extracted items (e.g. a 5-trade
 * contract note held back by the sender-trust check) still gets one row per item instead of
 * every later item overwriting the previous one under the same key.
 */
@Entity
@Table(name = "email_review_items",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "gmail_message_id", "item_index"}),
       indexes = {
           @Index(name = "idx_eri_user_status", columnList = "user_id, status"),
           @Index(name = "idx_eri_user_created", columnList = "user_id, created_at")
       })
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class EmailReviewItem {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "gmail_message_id", length = 100)
    private String gmailMessageId;

    /** Which extracted item within the email this row represents — 0 when the email yields a
     *  single item. Together with (user_id, gmail_message_id) this is the uniqueness key, so a
     *  multi-item email gets one row per item instead of the last item silently winning. */
    @Column(name = "item_index", nullable = false)
    @Builder.Default
    private int itemIndex = 0;

    @Column(length = 320)
    private String sender;

    @Column(length = 500)
    private String subject;

    /** The model's semantic classification, e.g. INTERNAL_TRANSFER. */
    @Column(name = "proposed_type", length = 40)
    private String proposedType;

    @Column(precision = 5, scale = 4)
    private BigDecimal confidence;

    /** Why a human is needed — shown verbatim in the queue. */
    @Column(name = "review_reason", length = 500)
    private String reviewReason;

    @Column(columnDefinition = "text")
    private String reasoning;

    /** The exact sentence the figures were read from, so a reviewer can check without
     *  reopening the original email. */
    @Column(columnDefinition = "text")
    private String evidence;

    /** The model's extracted fields as raw JSON — the starting point for an EDIT. */
    @Column(name = "extracted_fields", columnDefinition = "text")
    private String extractedFields;

    /* Denormalised headline figures so the queue can be listed without parsing JSON. */
    @Column(precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "transaction_date")
    private LocalDate transactionDate;

    @Column(length = 200)
    private String counterparty;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ReviewStatus status = ReviewStatus.PENDING;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolution_note", length = 500)
    private String resolutionNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }
}
