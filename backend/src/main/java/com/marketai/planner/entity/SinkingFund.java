package com.marketai.planner.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A dedicated multi-month savings fund — the PDF's "Annual Travel Fund Tracker" page (Vacation /
 * Trip Fund, Domestic Home Travel Fund). Distinct from a one-shot {@code FinancialGoal}: this is
 * a recurring monthly contribution building toward an annual target, tracked as its own ledger
 * ({@link SinkingFundEntry}) rather than a single running balance.
 */
@Entity
@Table(name = "sinking_funds", indexes = @Index(name = "idx_sinkfund_user", columnList = "user_id"))
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class SinkingFund {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "monthly_planned", precision = 12, scale = 2)
    private BigDecimal monthlyPlanned;

    @Column(name = "annual_target", precision = 12, scale = 2)
    private BigDecimal annualTarget;

    @Column(name = "sort_order")
    private Integer sortOrder;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
