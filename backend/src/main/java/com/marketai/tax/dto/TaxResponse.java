package com.marketai.tax.dto;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TaxResponse {
    private String fyLabel;              // e.g. FY 2026-27
    private BigDecimal capitalGains;     // realised (from recorded sales), after set-off
    private BigDecimal shortTermGains;   // after set-off
    private BigDecimal longTermGains;    // after set-off, before the yearly exemption
    private BigDecimal dividendIncome;
    private BigDecimal interestIncome;
    private BigDecimal salaryIncome;
    private BigDecimal totalTaxableInvestmentIncome; // dividends + interest + capital gains
    private BigDecimal estimatedTaxLow;
    private BigDecimal estimatedTaxHigh;
    /** TDS recorded on dividends and interest this year; already paid, so it comes off what is still due. */
    private BigDecimal tdsDeducted;
    private List<String> notes;
}
