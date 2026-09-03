package com.marketai.tax.dto;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TaxResponse {
    private String fyLabel;              // e.g. FY 2026-27
    private BigDecimal capitalGains;     // realised (from recorded sales)
    private BigDecimal dividendIncome;
    private BigDecimal interestIncome;
    private BigDecimal salaryIncome;
    private BigDecimal totalTaxableInvestmentIncome; // dividends + interest + capital gains
    private BigDecimal estimatedTaxLow;
    private BigDecimal estimatedTaxHigh;
    private List<String> notes;
}
