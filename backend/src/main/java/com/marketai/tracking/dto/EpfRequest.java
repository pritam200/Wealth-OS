package com.marketai.tracking.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class EpfRequest {
    private String employer;
    private BigDecimal currentBalance;
    private BigDecimal monthlyContribution;
    private BigDecimal rate;
    private LocalDate asOfDate;
}
