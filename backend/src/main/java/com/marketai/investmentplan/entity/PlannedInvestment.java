package com.marketai.investmentplan.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * "How much I intend to invest this month, from which account, into what" (spec §3/§5) —
 * a planning/tracking record, never a financial transaction. Matching it against real
 * ledger activity (LedgerTransfer/RecurringInvestment/RecurringDeposit) is entirely the
 * job of {@code PlannedInvestmentMatcher} (Phase 4); nothing here ever feeds
 * PortfolioContextService or any net-worth calculation — see spec §15.
 */
@Entity
@Table(name = "planned_investments", indexes = {
    @Index(name = "idx_planned_investment_user", columnList = "user_id"),
    @Index(name = "idx_planned_investment_month", columnList = "plan_month")
})
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class PlannedInvestment {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // First-of-month marker for "which month's plan this belongs to" (spec §17: historical
    // months are never deleted, only ever added to).
    @Column(name = "plan_month", nullable = false)
    private LocalDate month;

    @Column(name = "source_account_id")
    private Long sourceAccountId;

    @Column(name = "planned_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal plannedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "investment_type", nullable = false, length = 20)
    private InvestmentType investmentType;

    @Column(name = "destination_ref", length = 200)
    private String destinationRef; // fund name, broker, bank — free text, spec §5/§8

    // false = a manual monthly lump-sum entry (spec §5); true = this line represents an
    // already-scheduled RecurringInvestment/RecurringDeposit installment for the month
    // (spec §10) rather than a fresh manual amount.
    @Builder.Default
    @Column(nullable = false)
    private boolean scheduled = false;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PlanStatus status = PlanStatus.PLANNED;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    // Mirrors LedgerTransfer.DestinationType, minus CASH_ACCOUNT/EXTERNAL (a plan is always
    // about investing money, never a plain cash movement) — kept as its own enum rather than
    // reused directly so this table never depends on the ledger package's enum shape.
    public enum InvestmentType { MUTUAL_FUND, STOCK, FD, RD, EPF, OTHER }

    public enum PlanStatus { PLANNED, PARTIAL, COMPLETE, OVER_INVESTED }
}
