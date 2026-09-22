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
})
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

    @Column(name = "source_email_id", length = 100)
    private String sourceEmailId;

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

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
