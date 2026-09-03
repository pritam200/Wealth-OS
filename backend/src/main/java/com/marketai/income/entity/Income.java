package com.marketai.income.entity;

import javax.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "incomes", indexes = {
    @Index(name = "idx_income_user", columnList = "user_id"),
    @Index(name = "idx_income_date", columnList = "income_date")
})
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Income {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 200)
    private String description;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 50)
    private String source; // Salary, Freelance, Dividend, Interest, Rental, Business, Other

    @Column(name = "income_date", nullable = false)
    private LocalDate incomeDate;

    @Column(length = 200)
    private String payer;

    @Column(name = "payment_method", length = 100)
    private String paymentMethod;

    @Column(name = "source_email_id", length = 100)
    private String sourceEmailId;

    @Column(length = 500)
    private String note;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
