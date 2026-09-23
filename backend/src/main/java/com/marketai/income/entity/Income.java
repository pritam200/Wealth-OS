package com.marketai.income.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "incomes", indexes = {
    @Index(name = "idx_income_user", columnList = "user_id"),
    @Index(name = "idx_income_date", columnList = "income_date")
    },
    // NULL source_email_id (a manual entry) is not constrained by this — Postgres treats every
    // NULL as distinct in a unique index, so only two rows citing the SAME email can collide.
    // Safe to add only after the live cross-day-fallback duplicates were cleaned up (see
    // docs/DUPLICATE_DATA_CLEANUP_2026-09-23.md) — the underlying dedup bug is fixed in
    // ParsedEmailImporter.isDuplicateIncome.
    uniqueConstraints = @UniqueConstraint(name = "uq_income_user_source_email",
        columnNames = {"user_id", "source_email_id"}))
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

    // Stored as its display label via IncomeSourceConverter, so pre-enum rows read back
    // unchanged while new writes are constrained to the canonical taxonomy.
    @Column(nullable = false, length = 50)
    private IncomeSource source;

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
