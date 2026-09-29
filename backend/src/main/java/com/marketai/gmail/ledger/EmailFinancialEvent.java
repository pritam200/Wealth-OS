package com.marketai.gmail.ledger;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One financial event found in one email (its body or one of its attachments), with where it
 * ended up and why. The per-email manifest and the end-of-sync coverage check are both read
 * from here, so an event that was not booked is always visible with its reason.
 *
 * <p>{@link #eventKey} identifies the event within its email from what the extractor read
 * (document, kind, amount, date and repeat number), so a re-read updates the same row instead
 * of adding another. Financial records themselves are never written from here.
 */
@Entity
@Table(name = "email_financial_events",
       uniqueConstraints = @UniqueConstraint(name = "uk_efe_user_msg_key",
           columnNames = {"user_id", "gmail_message_id", "event_key"}),
       indexes = {
           @Index(name = "idx_efe_user_state", columnList = "user_id, state"),
           @Index(name = "idx_efe_user_seen", columnList = "user_id, last_seen_at")
       })
@Data @Builder(toBuilder = true) @NoArgsConstructor @AllArgsConstructor
public class EmailFinancialEvent {

    public static final String BODY = "BODY";
    public static final String ATTACHMENT = "ATTACHMENT";
    /** The email as a whole: an unread part, a totals check, an attachment that could not be opened. */
    public static final String EMAIL = "EMAIL";

    public static final String BY_SYSTEM = "SYSTEM";
    public static final String BY_USER = "USER";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "gmail_message_id", nullable = false, length = 100)
    private String gmailMessageId;

    @Column(name = "event_key", nullable = false, length = 120)
    private String eventKey;

    /** The matching review-queue item, when there is one. */
    @Column(name = "item_index")
    private Integer itemIndex;

    @Column(name = "source_kind", length = 20)
    private String sourceKind;

    @Column(name = "attachment_id", length = 500)
    private String attachmentId;

    @Column(name = "attachment_name", length = 255)
    private String attachmentName;

    @Column(name = "document_hash", length = 64)
    private String documentHash;

    /** Who the document says it is from (the sending domain when the model named no issuer). */
    @Column(name = "statement_provider", length = 120)
    private String statementProvider;

    @Column(name = "llm_provider", length = 40)
    private String llmProvider;

    @Column(name = "llm_model", length = 120)
    private String llmModel;

    @Column(name = "prompt_version", length = 60)
    private String promptVersion;

    @Column(name = "extraction_method", length = 30)
    private String extractionMethod;

    @Column(name = "extracted_at")
    private LocalDateTime extractedAt;

    private Double confidence;

    /** What the extractor said it was, e.g. BANK/DEBIT/FEE or MF/SIP. */
    @Column(name = "event_type", length = 60)
    private String eventType;

    /** SUCCESS, FAILED, PENDING, REVERSED, CANCELLED, REFUNDED, PARTIALLY_REFUNDED. */
    @Column(name = "event_status", length = 30)
    private String eventStatus;

    @Column(precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(length = 8)
    private String currency;

    @Column(name = "event_date")
    private LocalDate eventDate;

    /** UNKNOWN when the document names none — kept, never dropped. */
    @Column(length = 200)
    private String merchant;

    @Column(length = 100)
    private String account;

    @Column(length = 200)
    private String instrument;

    @Column(length = 100)
    private String reference;

    /** The source line, with long digit runs (account/card numbers) masked. */
    @Column(length = 500)
    private String evidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EventState state;

    @Column(length = 1000)
    private String reason;

    /** VERIFIED, FAILED or NOT_CHECKED — whether the figures were found in the source. */
    @Column(name = "validation_status", length = 20)
    private String validationStatus;

    /** NEW, DUPLICATE, CONFLICT or NOT_CHECKED. */
    @Column(name = "dedup_status", length = 20)
    private String dedupStatus;

    /** SYSTEM, or USER once a person decided it — a re-read never overrides a person. */
    @Column(name = "resolved_by", length = 10)
    private String resolvedBy;

    @Column(name = "first_seen_at")
    private LocalDateTime firstSeenAt;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;
}
