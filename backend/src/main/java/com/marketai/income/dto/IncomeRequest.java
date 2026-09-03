package com.marketai.income.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class IncomeRequest {
    private String description;
    private BigDecimal amount;
    private String source;
    private LocalDate incomeDate;
    private String note;
}
