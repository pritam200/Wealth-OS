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
    // One row per extracted line: an email (a bank statement) legitimately produces many rows, so
    // the key is the email plus the line's event fingerprint. It used to be the email alone, which
    // made every line after the first fail to insert. NULLs are distinct in a Postgres unique
    // index, so manual entries (no email) are never constrained by it.
    uniqueConstraints = @UniqueConstraint(name = "uq_income_user_source_line",
        columnNames = {"user_id", "source_email_id", "source_fingerprint"}))
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

    /** Event fingerprint of the email line this row was booked from (null for manual entries). */
    @Column(name = "source_fingerprint", length = 64)
    private String sourceFingerprint;

    @Column(length = 500)
    private String note;

    /** Tax deducted at source from this income, when stated. {@code amount} is the gross figure. */
    @Column(precision = 18, scale = 2)
    private BigDecimal tds;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
