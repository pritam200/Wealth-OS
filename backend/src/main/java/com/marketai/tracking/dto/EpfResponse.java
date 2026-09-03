package com.marketai.tracking.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data @Builder
public class EpfResponse {
    private Long id;
    private String employer;
    private BigDecimal currentBalance;
    private BigDecimal monthlyContribution;
    private BigDecimal rate;
    private LocalDate asOfDate;
    // computed
    private BigDecimal projected5Y;   // balance + contributions compounded for 5 years
    private BigDecimal annualInterest; // approx interest on current balance for 1 year
}
