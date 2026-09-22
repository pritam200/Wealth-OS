package com.marketai.planner.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One row of the household budget plan — the digital equivalent of one line in the PDF's
 * "MONTHLY HOUSEHOLD BUDGET" table (Rent, Groceries, Petrol/Fuel, ...). Seeded per-user from
 * {@link PlanCategoryDefaults} on first use, then fully user-editable: rename, re-group, change
 * the planned amount, add a custom category, or deactivate one — nothing about the taxonomy is
 * hardcoded into {@code PlannerService}/{@code PlanCategoryClassifier}, only the one-time seed
 * values are (per "these should be configurable rather than hardcoded into business logic").
 *
 * <p>{@code plannedAmount} is a single editable-anytime figure rather than a per-month snapshot:
 * the source PDF itself has one static planned column per category, not a value that changes
 * month to month, so a full month-versioned plan table would be effort spent on a case the
 * document doesn't actually have. Editing it going forward changes future months' plan; past
 * months' actuals are untouched because they come from dated {@code Expense} rows, not from this
 * row's current value.
 */
@Entity
@Table(name = "plan_categories", indexes = {
    @Index(name = "idx_plancat_user", columnList = "user_id"),
    @Index(name = "idx_plancat_user_key", columnList = "user_id, category_key")
})
@Data @NoArgsConstructor @AllArgsConstructor @Builder(toBuilder = true)
public class PlanCategory {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Stable machine key (e.g. "GROCERIES") — what {@code Expense.planCategoryOverride} stores. */
    @Column(name = "category_key", nullable = false, length = 60)
    private String key;

    @Column(nullable = false, length = 120)
    private String name;

    /** Month-end review grouping (e.g. "Food & Household") — free text, not an enum, by design. */
    @Column(name = "group_name", length = 120)
    private String groupName;

    @Column(name = "planned_amount", precision = 12, scale = 2)
    private BigDecimal plannedAmount;

    @Column(name = "sort_order")
    private Integer sortOrder;

    /**
     * Comma-separated, lower-case substring rules used by {@code PlanCategoryClassifier} to
     * bucket a merchant/description into this category. User-editable so a household's own
     * vocabulary (a specific maid-payment UPI handle, a local kirana store name) can be added
     * without a code change.
     */
    @Column(length = 1000)
    private String keywords;

    /** True for exactly one category (Miscellaneous) — the classifier's last resort. */
    @Column(name = "is_fallback", nullable = false)
    @Builder.Default
    private boolean fallback = false;

    /**
     * When set, this category's "actual" is read from {@code SinkingFundEntry.added} for the
     * named fund/month instead of from classified Expense rows — the PDF tracks Trip/Vacation
     * Savings and Domestic Home Travel as fund contributions on their own ledger page, not as
     * ordinary spend.
     */
    @Column(name = "linked_sinking_fund_name", length = 120)
    private String linkedSinkingFundName;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
