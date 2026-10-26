package com.marketai.goal.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "financial_goals", indexes = @Index(name = "idx_goal_user", columnList = "user_id"))
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class FinancialGoal {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 120)
    private String name;              // Retirement, House, Car…

    @Column(length = 40)
    private String category;          // Retirement | Home | Car | Education | Travel | Emergency | Other

    @Column(name = "target_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal targetAmount;

    @Column(name = "current_saved", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal currentSaved = BigDecimal.ZERO;

    @Column(name = "monthly_contribution", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal monthlyContribution = BigDecimal.ZERO;

    @Column(name = "expected_return", precision = 6, scale = 2)
    @Builder.Default
    private BigDecimal expectedReturn = new BigDecimal("10"); // annual %

    @Column(name = "target_date")
    private LocalDate targetDate;

    /** Annual % by which the target grows in rupees by the target date; the target is in today's rupees. Null on goals made before inflation was asked for, read as 0. */
    @Column(name = "inflation_rate", precision = 6, scale = 2)
    private BigDecimal inflationRate;

    /** Holdings counted toward the goal at their current value, on top of {@link #currentSaved}. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "goal_linked_holdings", joinColumns = @JoinColumn(name = "goal_id"))
    @Column(name = "holding_id")
    @Builder.Default
    private Set<Long> linkedHoldingIds = new HashSet<>();

    /** Active SIPs counted toward the goal's monthly contribution, on top of {@link #monthlyContribution}. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "goal_linked_sips", joinColumns = @JoinColumn(name = "goal_id"))
    @Column(name = "recurring_investment_id")
    @Builder.Default
    private Set<Long> linkedSipIds = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @org.hibernate.annotations.UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
