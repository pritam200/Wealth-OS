package com.marketai.income.dto;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class IncomeResponse {
    private Long id;
    private String description;
    private BigDecimal amount;
    private String source;
    private LocalDate incomeDate;
    private String payer;
    private String paymentMethod;
    private String sourceEmailId;
    private String note;
    private LocalDateTime createdAt;
}
