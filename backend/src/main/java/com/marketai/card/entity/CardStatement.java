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
    },
    // NULL source_email_id (a manual entry) is not constrained by this — Postgres treats every
    // NULL as distinct in a unique index, so only two rows citing the SAME email can collide.
    uniqueConstraints = @UniqueConstraint(name = "uq_card_statement_user_source_email",
        columnNames = {"user_id", "source_email_id"}))
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

    /**
     * Set when the statement's own arithmetic doesn't hold — previous balance + this cycle's
     * debits − this cycle's credits doesn't reconcile to {@link #totalDue} within ₹1, checked by
     * {@code ArithmeticValidator.statementBalances}. This is the strongest available signal that
     * a figure was misread (a hallucinated leading digit, an OCR error), because it uses the
     * statement's own internal redundancy rather than trusting any single extracted number.
     * Never silently corrected — the mismatch is surfaced, not "fixed."
     */
    @Column(name = "arithmetic_mismatch")
    private Boolean arithmeticMismatch;

    @Column(name = "arithmetic_mismatch_detail", length = 500)
    private String arithmeticMismatchDetail;

    @Column(name = "source_email_id", length = 100)
    private String sourceEmailId;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "timestamp not null default now()")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
