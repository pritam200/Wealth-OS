package com.marketai.goal.entity;

import javax.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

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

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
