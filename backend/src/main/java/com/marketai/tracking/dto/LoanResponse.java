package com.marketai.tracking.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data @Builder
public class LoanResponse {
    private Long id;
    private String name;
    private String type;
    private BigDecimal emi;
    private BigDecimal outstanding;
    private BigDecimal rate;
    private int remainingMonths;
    // computed
    private BigDecimal totalPayable;
    private BigDecimal totalInterestPayable;
    private double repaidPercent; // 0-100
}
