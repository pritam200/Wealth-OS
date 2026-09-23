package com.marketai.rent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One row per calendar month of rent, whether generated from an active
 * {@link RentSchedule} (scheduleId set) or entered as a one-time payment
 * (scheduleId null). {@code paidDate} null means "upcoming" — the placeholder for a
 * not-yet-paid month; setting it is what flips status to PAID. Never rolled into
 * {@code Expense}: rent stays in its own ledger so a schedule-generated placeholder
 * and its eventual real payment are always the same row, not two.
 */
@Entity
@Table(name = "rents", indexes = {
    @Index(name = "idx_rent_user", columnList = "user_id"),
    @Index(name = "idx_rent_month", columnList = "rent_month")
})
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Rent {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "schedule_id")
    private Long scheduleId;

    // First-of-month marker for "which month is this rent for" — paidDate (when set)
    // is the actual payment date and can fall in a different calendar month.
    @Column(name = "rent_month", nullable = false)
    private LocalDate month;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "paid_date")
    private LocalDate paidDate;

    @Column(name = "paid_to", length = 200)
    private String paidTo;

    @Column(name = "cash_account_id")
    private Long cashAccountId;

    @Column(name = "payment_method", length = 100)
    private String paymentMethod;

    @Column(name = "reference_id", length = 100)
    private String referenceId;

    @Column(length = 500)
    private String note;

    @Column(name = "source_email_id", length = 100)
    private String sourceEmailId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
