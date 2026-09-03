package com.marketai.scheduled.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data @Builder
public class RecurringInvestmentResponse {
    private Long id;
    private String type;
    private String label;
    private String linkedSymbol;
    private BigDecimal amount;
    private LocalDate startDate;
    private Integer tenureMonths;
    private String status;
    private List<InstallmentStatus> installments; // recent history + near-future, upcoming/completed/missed
    private int completedCount;
    private int missedCount;
}
