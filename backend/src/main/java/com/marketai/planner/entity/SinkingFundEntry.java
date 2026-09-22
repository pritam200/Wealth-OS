package com.marketai.planner.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * One month's row in a {@link SinkingFund}'s ledger — mirrors the PDF's per-month
 * PLANNED/ADDED/USED/BALANCE columns exactly. {@code yearMonth} is "yyyy-MM" (e.g. "2026-09")
 * rather than a date, since a fund entry has no day-of-month meaning. Balance is deliberately
 * NOT a stored column — it is always the running sum of (added - used) up to and including this
 * month, computed by {@code SinkingFundService} at read time so it can never drift from the
 * entries it's derived from.
 */
@Entity
@Table(name = "sinking_fund_entries",
       uniqueConstraints = @UniqueConstraint(columnNames = {"fund_id", "year_month"}),
       indexes = @Index(name = "idx_sinkentry_fund", columnList = "fund_id"))
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class SinkingFundEntry {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fund_id", nullable = false)
    private Long fundId;

    @Column(name = "year_month", nullable = false, length = 7)
    private String yearMonth;

    @Column(precision = 12, scale = 2)
    private BigDecimal planned;

    @Column(precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal added = BigDecimal.ZERO;

    @Column(precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal used = BigDecimal.ZERO;
}
