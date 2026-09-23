package com.marketai.rent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A recurring rent definition — due day + amount, mirroring RecurringInvestment's
 * shape. Materialized into one {@link Rent} placeholder row per month by RentService,
 * rather than computed on the fly, since a payment (manual or Gmail-detected) needs a
 * real row to match onto.
 */
@Entity
@Table(name = "rent_schedules", indexes = @Index(name = "idx_rent_schedule_user", columnList = "user_id"))
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class RentSchedule {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "due_day_of_month", nullable = false)
    private Integer dueDayOfMonth;

    @Column(name = "paid_to", length = 200)
    private String paidTo;

    @Column(name = "cash_account_id")
    private Long cashAccountId;

    @Column(name = "payment_method", length = 100)
    private String paymentMethod;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
