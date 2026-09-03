package com.marketai.scheduled.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class RecurringInvestmentRequest {
    private String type; // SIP | PPF | NPS
    private String label;
    private String linkedSymbol;
    private BigDecimal amount;
    private LocalDate startDate;
    private Integer tenureMonths;
    private String status;
}
