package com.marketai.scheduled.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One row per amount/status change to a {@link RecurringInvestment}, so "Jan–Jun ₹5,000,
 * Jul–Sep ₹7,500, Oct onward ₹10,000" is reconstructable (spec §18). Written only by
 * {@code RecurringInvestmentService.update()} — the schedule itself always holds only its
 * current value; past values live here, never rewritten.
 */
@Entity
@Table(name = "recurring_investment_history",
    indexes = @Index(name = "idx_ri_history_ri", columnList = "recurring_investment_id"))
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class RecurringInvestmentHistory {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recurring_investment_id", nullable = false)
    private Long recurringInvestmentId;

    @Column(name = "changed_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime changedAt = LocalDateTime.now();

    @Column(nullable = false, length = 30)
    private String field; // "amount" | "status"

    @Column(name = "old_value", length = 100)
    private String oldValue;

    @Column(name = "new_value", length = 100)
    private String newValue;
}
