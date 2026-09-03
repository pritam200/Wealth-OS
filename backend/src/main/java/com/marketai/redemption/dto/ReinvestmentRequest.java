package com.marketai.redemption.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class ReinvestmentRequest {
    private BigDecimal amount;
    private LocalDate date;
    private String targetFund;
    private String note;
}
