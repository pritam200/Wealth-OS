package com.marketai.card.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One transaction line printed on a card statement, and what the tally found for it: an expense
 * already on record (MATCHED), an expense added because no earlier sync had it (ADDED), a debit
 * still without an expense (MISSING — no date to book it on, or removed by the user as IGNORED),
 * or a payment/refund (CREDIT), which is never an expense.
 */
@Entity
@Table(name = "card_statement_lines", indexes = {
    @Index(name = "idx_csl_statement", columnList = "statement_id"),
    @Index(name = "idx_csl_user_expense", columnList = "user_id, matched_expense_id")
})
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class CardStatementLine {

    public enum Status { MATCHED, ADDED, MISSING, IGNORED, CREDIT }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "statement_id", nullable = false)
    private Long statementId;

    @Column(name = "card_id", nullable = false)
    private Long cardId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "txn_date")
    private LocalDate txnDate;

    @Column(length = 200)
    private String merchant;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private boolean debit;

    @Column(length = 8)
    private String currency;

    @Column(length = 60)
    private String category;

    @Column(length = 500)
    private String evidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_status", length = 12, nullable = false)
    private Status status;

    @Column(name = "matched_expense_id")
    private Long matchedExpenseId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
