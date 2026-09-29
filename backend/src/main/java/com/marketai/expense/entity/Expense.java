package com.marketai.expense.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "expenses", indexes = {
    @Index(name = "idx_expense_user", columnList = "user_id"),
    @Index(name = "idx_expense_date", columnList = "expense_date")
    },
    // One row per extracted line: an email (a bank statement) legitimately produces many rows, so
    // the key is the email plus the line's event fingerprint. It used to be the email alone, which
    // made every line after the first fail to insert. NULLs are distinct in a Postgres unique
    // index, so manual entries (no email) are never constrained by it.
    uniqueConstraints = @UniqueConstraint(name = "uq_expense_user_source_line",
        columnNames = {"user_id", "source_email_id", "source_fingerprint"}))
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Expense {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 200)
    private String description;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 50)
    private ExpenseCategory category;

    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    @Column(length = 200)
    private String merchant;

    @Column(name = "payment_method", length = 100)
    private String paymentMethod;

    // Which CashAccount this was paid from, when the user chooses to say — bare Long rather
    // than a JPA relation, consistent with this entity's existing user_id/source_email_id
    // style. Nullable: most historical rows and quick manual entries never set this.
    @Column(name = "cash_account_id")
    private Long cashAccountId;

    @Column(name = "source_email_id", length = 100)
    private String sourceEmailId;

    /** Event fingerprint of the email line this row was booked from (null for manual entries). */
    @Column(name = "source_fingerprint", length = 64)
    private String sourceFingerprint;

    @Column(length = 500)
    private String note;

    /**
     * The household planner's fine-grained category key (e.g. "GROCERIES"), set only when the
     * user explicitly moves/overrides this expense away from whatever
     * {@code PlanCategoryClassifier} would assign at read time. Null means "not overridden — use
     * the live classifier result," not "uncategorized"; this is deliberately the ONLY thing
     * persisted for planner categorization; the classifier's own (possibly different, if the
     * user later edits category keywords) answer is always recomputed, never stored, so it can
     * never go stale. Nullable/additive so existing rows need no backfill.
     */
    @Column(name = "plan_category_override", length = 60)
    private String planCategoryOverride;

    /**
     * Set on a refund: the purchase it reverses. A refund is stored as a negative amount in the
     * purchase's category, so the month's spend and the category total both fall by what came
     * back, and the original purchase is never edited.
     */
    @Column(name = "refund_of_expense_id")
    private Long refundOfExpenseId;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
