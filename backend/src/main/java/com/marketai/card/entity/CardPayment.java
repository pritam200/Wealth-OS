package com.marketai.card.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One payment-confirmation fact from the issuer — status detection and reconciliation only.
 * Wealth-OS never initiates a payment; this row exists purely to explain "did I pay" and
 * "how much is outstanding" from evidence the bank already sent.
 *
 * <p>{@code cardId} is nullable: a payment confirmation is still worth keeping even when it
 * can't be matched to a saved card (no last-four/issuer match yet) — never silently dropped,
 * just parked unreconciled until a card link can be made.
 */
@Entity
@Table(name = "card_payments", indexes = {
    @Index(name = "idx_card_payment_card", columnList = "card_id")
    },
    // NULL source_email_id (a manual entry) is not constrained by this — Postgres treats every
    // NULL as distinct in a unique index, so only two rows citing the SAME email can collide.
    uniqueConstraints = @UniqueConstraint(name = "uq_card_payment_user_source_email",
        columnNames = {"user_id", "source_email_id"}))
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class CardPayment {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "card_id")
    private Long cardId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;

    @Column(name = "reference_number", length = 64)
    private String referenceNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private CardPaymentStatus status = CardPaymentStatus.CONFIRMED;

    @Column(name = "source_email_id", length = 100)
    private String sourceEmailId;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "timestamp not null default now()")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
