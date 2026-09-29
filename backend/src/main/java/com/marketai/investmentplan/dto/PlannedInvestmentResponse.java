package com.marketai.investmentplan.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data @Builder
public class PlannedInvestmentResponse {
    private Long id;
    private LocalDate month;
    private Long sourceAccountId;
    private BigDecimal plannedAmount;
    private String investmentType;
    private String destinationRef;
    private boolean scheduled;
    private LocalDate dueDate;
    private String status; // PLANNED | PARTIAL | COMPLETE | OVER_INVESTED
    private BigDecimal actualAmount;    // sum of matched InvestmentReconciliation rows
    private BigDecimal remainingAmount; // max(planned - actual, 0)
    private BigDecimal overInvestedAmount; // max(actual - planned, 0)
    private double completionPercent;
    /**
     * Where the money is: PLANNED (nothing moved), FUNDED (sent to the broker/AMC/bank but not
     * all of it invested yet), INVESTED (invested, but less than planned), SETTLED (the planned
     * amount has been invested).
     */
    private String stage;
    private BigDecimal fundedAmount;         // transferred out of a cash account for this line
    private BigDecimal investedAmount;       // units bought / instalments paid for this line
    private BigDecimal awaitingInvestment;   // funded but not yet invested

}
