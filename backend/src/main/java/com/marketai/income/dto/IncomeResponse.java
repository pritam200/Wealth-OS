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
    /** True when nothing was added because this transaction was already recorded; the existing
     *  record is returned. */
    private boolean alreadyRecorded;
    private String note;
    private LocalDateTime createdAt;
}
