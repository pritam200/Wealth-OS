package com.marketai.tracking.entity;

import com.marketai.auth.entity.User;
import lombok.*;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "loans")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Loan {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 100)
    @Builder.Default
    private String type = "Other";

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal emi;

    @Column(precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal outstanding = BigDecimal.ZERO;

    @Column(precision = 6, scale = 3)
    @Builder.Default
    private BigDecimal rate = BigDecimal.ZERO;

    @Column(name = "remaining_months")
    @Builder.Default
    private int remainingMonths = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
