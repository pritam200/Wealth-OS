package com.marketai.investmentplan.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Evidence that some real, already-booked financial activity was matched onto a
 * {@link PlannedInvestment} line (spec §7/§20) — never itself a financial transaction,
 * never joined into any net-worth calculation. {@code sourceKind}/{@code sourceRef} point
 * at the real record (a LedgerTransfer id, or a RecurringInvestment/RecurringDeposit
 * installment) purely for traceability/display; deleting a plan or a reconciliation row
 * never touches that real record.
 */
@Entity
@Table(name = "investment_reconciliations",
    indexes = @Index(name = "idx_investment_reconciliation_plan", columnList = "plan_id"))
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class InvestmentReconciliation {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "source_kind", nullable = false, length = 30)
    private String sourceKind; // "LEDGER_TRANSFER" | "RECURRING_INVESTMENT" | "RECURRING_DEPOSIT"

    @Column(name = "source_ref", nullable = false, length = 100)
    private String sourceRef; // the source row's id, as a string

    @Column(name = "matched_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal matchedAmount;

    @Column(name = "match_confidence", nullable = false)
    private double matchConfidence;

    @Column(name = "matched_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime matchedAt = LocalDateTime.now();
}
