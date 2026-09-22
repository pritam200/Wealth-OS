package com.marketai.card.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One earn/redeem/expire/adjust entry against a card's points balance. The balance itself is
 * never stored directly once a card has ledger history — it is always the sum of these rows,
 * the same "ledger as source of truth" principle already applied to Holding.quantity.
 */
@Entity
@Table(name = "reward_transactions", indexes = {
    @Index(name = "idx_reward_txn_card", columnList = "card_id")
})
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class RewardTransaction {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "card_id", nullable = false)
    private Long cardId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RewardTransactionType type;

    /** Signed — positive for EARN, negative for REDEEM/EXPIRE. ADJUST can be either sign. */
    @Column(nullable = false)
    private Integer points;

    /** Cash value at the time of this entry, if known. Informational only — never used to
     *  derive the points balance itself. */
    @Column(name = "monetary_value", precision = 12, scale = 2)
    private BigDecimal monetaryValue;

    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    @Column(length = 300)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "timestamp not null default now()")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
