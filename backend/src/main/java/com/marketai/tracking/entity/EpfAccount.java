package com.marketai.tracking.entity;

import com.marketai.auth.entity.User;
import lombok.*;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "epf_accounts")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class EpfAccount {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(length = 150)
    private String employer;

    @Column(name = "current_balance", nullable = false, precision = 15, scale = 2)
    private BigDecimal currentBalance;

    @Column(name = "monthly_contribution", precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal monthlyContribution = BigDecimal.ZERO;

    @Column(nullable = false, precision = 6, scale = 3)
    @Builder.Default
    private BigDecimal rate = new BigDecimal("8.25"); // EPFO FY2024-25 rate

    @Column(name = "as_of_date")
    private LocalDate asOfDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
