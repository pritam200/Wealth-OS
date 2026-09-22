package com.marketai.card.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One billing-cycle statement fact from the issuer, as reported by a statement/bill email —
 * never mutated after creation. Reconciliation (outstanding balance vs. payments) is always
 * computed at read time from this plus {@link CardPayment}, never stored here, so there is
 * nothing for a payment to silently overwrite.
 */
@Entity
@Table(name = "card_statements", indexes = {
    @Index(name = "idx_card_statement_card", columnList = "card_id")
})
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class CardStatement {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "card_id", nullable = false)
    private Long cardId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Not every statement email states this explicitly. */
    @Column(name = "statement_date")
    private LocalDate statementDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "total_due", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalDue;

    @Column(name = "minimum_due", precision = 12, scale = 2)
    private BigDecimal minimumDue;

    @Column(name = "previous_balance", precision = 12, scale = 2)
    private BigDecimal previousBalance;

    @Column(name = "source_email_id", length = 100)
    private String sourceEmailId;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "timestamp not null default now()")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
