package com.marketai.card.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * A credit card seen in email (a statement or a payment confirmation) that matches no saved card.
 * Statements for it are kept here, as JSON, instead of being dropped, and are filed onto the card
 * once the user adds it or links it to an existing one.
 */
@Entity
@Table(name = "detected_cards", indexes = @Index(name = "idx_detected_card_user", columnList = "user_id, status"))
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class DetectedCard {

    public enum Status { PENDING, ADDED, LINKED, DISMISSED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(length = 60)
    private String issuer;

    @Column(name = "last_four", length = 4)
    private String lastFour;

    /** The card's name as the email gives it, e.g. "MoneyBack+" — a suggestion for the new card's name. */
    @Column(name = "suggested_name", length = 100)
    private String suggestedName;

    @Enumerated(EnumType.STRING)
    @Column(length = 12, nullable = false)
    private Status status;

    @Column(name = "seen_count", nullable = false)
    @Builder.Default
    private int seenCount = 1;

    /** Subject of the newest email it was seen in, to show the user where it came from. */
    @Column(name = "last_subject", length = 300)
    private String lastSubject;

    /** Statements waiting for this card: a JSON array of {gmailMessageId, facts}. */
    @Column(name = "pending_statements_json", columnDefinition = "text")
    private String pendingStatementsJson;

    @Column(name = "linked_card_id")
    private Long linkedCardId;

    @Column(name = "first_seen_at", nullable = false)
    @Builder.Default
    private LocalDateTime firstSeenAt = LocalDateTime.now();

    @Column(name = "last_seen_at", nullable = false)
    @Builder.Default
    private LocalDateTime lastSeenAt = LocalDateTime.now();
}
