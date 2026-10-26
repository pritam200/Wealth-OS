package com.marketai.expense.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * The category a user chose for a merchant, applied to that merchant's later expenses in place
 * of the keyword guess. Keyed on the normalised merchant name (PartyNames.normalise), so
 * "SWIGGY*BLR" and "Swiggy" share one rule.
 */
@Entity
@Table(name = "merchant_category_rules",
    uniqueConstraints = @UniqueConstraint(name = "uq_merchant_rule", columnNames = {"user_id", "merchant_key"}))
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class MerchantCategoryRule {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "merchant_key", nullable = false, length = 200)
    private String merchantKey;

    /** The merchant as the user saw it when choosing, for display. */
    @Column(name = "merchant", length = 200)
    private String merchant;

    @Column(nullable = false, length = 60)
    private ExpenseCategory category;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();
}
