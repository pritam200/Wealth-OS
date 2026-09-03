package com.marketai.tracking.entity;

import com.marketai.auth.entity.User;
import lombok.*;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "recurring_deposits")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class RecurringDeposit {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 200)
    private String bank;

    @Column(name = "monthly_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal monthlyAmount;

    @Column(nullable = false, precision = 6, scale = 3)
    private BigDecimal rate;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "tenure_months", nullable = false)
    private int tenureMonths;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
