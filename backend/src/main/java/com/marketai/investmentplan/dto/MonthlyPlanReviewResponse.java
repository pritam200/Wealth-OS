package com.marketai.investmentplan.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Month-end aggregate view (spec §6/§13/§16). Purely derived from PlannedInvestment +
 *  InvestmentReconciliation rows for the month — no state of its own. */
@Data @Builder
public class MonthlyPlanReviewResponse {
    private LocalDate month;
    private BigDecimal totalPlanned;
    private BigDecimal totalCompleted; // sum of actual amounts, capped per-line at planned
    private BigDecimal totalPending;
    private BigDecimal totalOverInvested;
    private double completionRate; // 0-100
    private List<PlannedInvestmentResponse> completed;
    private List<PlannedInvestmentResponse> pending;
    private List<PlannedInvestmentResponse> overInvested;
}
