package com.marketai.tracking.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data @Builder
public class RdResponse {
    private Long id;
    private String bank;
    private BigDecimal monthlyAmount;
    private BigDecimal rate;
    private LocalDate startDate;
    private int tenureMonths;
    // computed
    private int monthsElapsed;
    private BigDecimal totalDeposited;
    private BigDecimal currentValue;     // deposited + interest accrued to today
    private BigDecimal projectedCorpus;
    private BigDecimal interestEarned;
    private double progressPercent;
}
